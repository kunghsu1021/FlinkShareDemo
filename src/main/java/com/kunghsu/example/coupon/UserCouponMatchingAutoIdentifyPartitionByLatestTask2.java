package com.kunghsu.example.coupon;

import com.kunghsu.apache.flink.flinkkafka.config.FlinkKafkaConfig;
import com.kunghsu.common.utils.DateUtils;
import com.kunghsu.common.utils.JacksonUtils;
import com.kunghsu.example.coupon.function.UdfTimePeriodFunction;
import com.kunghsu.example.coupon.table.CouponInputTableVO2;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.ProcessingTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.apache.flink.table.api.Expressions.$;

/**
 * 实际案例--根据商户经纬度给匹配用户发券
 * 应用窗口（为了解决获取count重复问题，并且能知道何时送数结束）
 * 使用函数决定列名
 * 不用分流，使用双重join保证筛数为0时也有流数据产生
 *
 * 遇到一个小问题，假如同一个kafka消息，多次进入系统，会导致笛卡尔积的结果越来越大
 * 所以这里需要做一个排重处理
 * 两种方法：
 * 1.在窗口聚合里排重
 * 2.给kafka消息补上一个唯一ID
 *
 * 研究自动识别分区
 * 不在建表时指定分区，在SQL中指定Options
 * 能自动识别, 'streaming-source.partition.include' = 'latest' 亲测成功
 * 建表语句详见doc目录
 *
 * date_format(NOW(), 'yyyy-MM-dd HH:mm:ss') 在维表场景有问题，需要改用法，不能在维表SQL中使用 NOW()或者CURRENT_TIMESTAMP类似的SQL
 *
 * author:xuyaokun_kzx
 * date:2022/2/17
 * desc:
*/
public class UserCouponMatchingAutoIdentifyPartitionByLatestTask2 {

    private final static Logger LOGGER = LoggerFactory.getLogger(UserCouponMatchingAutoIdentifyPartitionByLatestTask2.class);

    public static void main(String[] args) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        EnvironmentSettings environmentSettings = EnvironmentSettings.newInstance().useBlinkPlanner().inStreamingMode().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, environmentSettings);

//        env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);

        FlinkKafkaConsumer<String> flinkKafkaConsumer = KafkaConsumerProducerConfig.getFlinkKafkaConsumer("coupon-input");
        //添加输入源
        DataStream<String> stream = env.addSource(flinkKafkaConsumer);
        SingleOutputStreamOperator<CouponInputTableVO2> stream2 = stream.map(new MapFunction<String, CouponInputMsg>() {
            @Override
            public CouponInputMsg map(String value) throws Exception {
                System.out.println("输入端入参：" + value);
                CouponInputMsg flinkTopicMsg = JacksonUtils.toJavaObject(value, CouponInputMsg.class);
                return flinkTopicMsg;
            }
        }).map(new MapFunction<CouponInputMsg, CouponInputTableVO2>() {
            @Override
            public CouponInputTableVO2 map(CouponInputMsg value) throws Exception {
                CouponInputTableVO2 couponInputTableVO = new CouponInputTableVO2();
                couponInputTableVO.setMessageType(value.getMESSAGE_TYPE());
                couponInputTableVO.setCouponId(value.getCOUPON_ID());
                couponInputTableVO.setStoreId(value.getSTORE_ID());
                couponInputTableVO.setStoreRange(value.getSTORE_RANGE());
                couponInputTableVO.setStoreLatitude(value.getSTORE_LATITUDE());
                couponInputTableVO.setStoreLongitude(value.getSTORE_LONGITUDE());
                couponInputTableVO.setUserNum(value.getUSER_NUM());
                couponInputTableVO.setUniqueReqId(value.getUNIQUE_REQ_ID());
                couponInputTableVO.setCreateTime(new Date());
                couponInputTableVO.setType("1");
                //时段
                couponInputTableVO.setDateString(DateUtils.now());
                System.out.println("分流前转换得到内容：" + JacksonUtils.toJSONString(couponInputTableVO));
                return couponInputTableVO;
            }
        });

        //通过流得到kafka table，将流转成表
        Table inputTable = tableEnv.fromDataStream(stream2, $("couponId"), $("storeId"),
                $("storeRange"), $("storeLongitude"), $("storeLatitude"),
                $("userNum"), $("uniqueReqId"), $("type"),
//                $("dateString"),
                $("proctime").proctime());

        //获取hive的表
        //hive相关属性
        //定义一个唯一的名称，这个值是可以随意定义的
        String catalogName = "myhive";
        //hive-site.xml的正确位置
        String hiveConfDir = "D:\\hive\\apache-hive-2.3.6-bin\\conf";
//        String version = "2.3.6";
        String version = "3.1.2";
        String database = "test";
        HiveCatalog hive = new HiveCatalog(catalogName, "default", hiveConfDir, version);

        tableEnv.registerCatalog(catalogName, hive);
        tableEnv.useCatalog(catalogName);
        tableEnv.useDatabase(database);
        //是否指定SqlDialect，不影响Options语法，可以不指定
//        tableEnv.getConfig().setSqlDialect(SqlDialect.HIVE);

        // 注册函数
        tableEnv.createTemporarySystemFunction("udfTimePeriod", UdfTimePeriodFunction.class);

        tableEnv.getConfig().getConfiguration().setString("table.dynamic-table-options.enabled", "true");

        //模拟表名：user_location_partition_info_temporal2  没有在建表语句中指定latest
        //在这里需要将要用到的列筛选出来
        /*
            OPTIONS能在单查hive维表时生效
        */
        String queryHiveSql = "SELECT cert_type, cert_nbr, lat, lng, lat_night, lng_night, work_day, destination, partstart "
                + " FROM user_location_partition_info_temporal2 "
                + "/*+ OPTIONS('streaming-source.enable'='true', 'streaming-source.partition.include' = 'latest'" +
                ", 'streaming-source.partition-order' = 'create-time', 'streaming-source.monitor-interval' = '20 s'" +
                ") */"
                ;
        Table hiveTable = tableEnv.sqlQuery(queryHiveSql);

        /*
            必须指定其中一列作为SYSTEM_TIME，如下的 FOR SYSTEM_TIME AS OF a.proctime AS b
            否则会报错：
            The only supported 'streaming-source.partition.include' is 'all' in hive table scan, but is 'latest'
         */
        //两个表进行连接join(单join会报错The only supported 'streaming-source.partition.include' is 'all' in hive table scan, but is 'latest')
        //因为join的时候没法同时通过API指定SYSTEM_TIME，这种写法不能成功
//        Table joinResTable = inputTable.join(hiveTable);

        /*
            OPTIONS放在join的时候，不奏效！！
            必须得放在首次查hive表时
         */
        Table joinResTable = tableEnv.sqlQuery("select a.*,b.* " +
                "from " + inputTable + " AS a join " + hiveTable
//                + " /*+ OPTIONS('streaming-source.enable'='true', 'streaming-source.partition.include' = 'latest'" +
//                        ", 'streaming-source.monitor-interval' = '20 s'" +
//                        ", 'streaming-source.partition-order' = 'create-time'" +
//                        ", 'streaming-source.partition-order' = 'partition-time'" +
//                        ", 'partition.time-extractor.kind' = 'default'" +
//                        ", 'partition.time-extractor.timestamp-pattern' = '$pt_year-$pt_month-$pt_day 00:00:00'" +
//                ") */"
                + " FOR SYSTEM_TIME AS OF a.proctime AS b " //
//                "  AS b " //不指定SYSTEM_TIME，是错误的
//                        + " /*+ OPTIONS('streaming-source.enable'='true', 'streaming-source.partition.include' = 'latest'" +
//                        ", 'streaming-source.partition-order' = 'create-time', 'streaming-source.monitor-interval' = '20 s'" +
//                        ") */"
                        + " on b.cert_type = a.type "
//                + " where b.cert_type is not null"
        );

        //表转成流
//        DataStream<Tuple2<Boolean, Row>> joinResTableResultStream = tableEnv.toRetractStream(joinResTable, Row.class);
//        joinResTableResultStream.print("joinResTableResultStream");

        //执行经纬度比较SQL
        //查出所有符合条件的行(多行)
        //列名的选择需要根据时间段动态变！自定义一个函数，提供这个列名
        /*
            streaming-source.partition.include 加在join这里也是不奏效的
                        必须得放在首次查hive表时
         */
        Table itemResultTable = tableEnv.sqlQuery(
                "select  t.cert_type, t.cert_nbr, a.couponId, a.storeId, a.storeRange, a.userNum, a.uniqueReqId  " +
                "from " + inputTable + " a left join " +
                "( " +
                "SELECT cert_type, cert_nbr, couponId, storeId, storeRange, userNum, uniqueReqId " +
                        "FROM " + joinResTable
                        +
//                        " /*+ OPTIONS('streaming-source.enable'='true', 'streaming-source.partition.include' = 'latest'" +
//                        ", 'streaming-source.partition-order' = 'create-time', 'streaming-source.monitor-interval' = '20 s'" +
//                        ") */"  +

                        " where ROUND(6378.138 * 2 * ASIN(SQRT(\n" +
                        "POWER(SIN((CAST(storeLatitude as double) * PI() / 180 - CAST(" +
                        "(CASE udfTimePeriod(couponId)\n" +
                        "WHEN '0' THEN lat\n" +
                        "WHEN '1' THEN lat_night\n" +
                        "ELSE lat_night \n" +
                        "END) " +
                        " as double) * PI() / 180) / 2), 2)\n" +
                        "+ COS(CAST(storeLatitude as double) * PI() / 180) * COS(CAST(" +
                        "(CASE udfTimePeriod(couponId)\n" +
                        "WHEN '0' THEN lat\n" +
                        "WHEN '1' THEN lat_night\n" +
                        "ELSE lat_night \n" +
                        "END) " +
                        " as double) * PI() / 180) * \n" +
                        "POWER(SIN((CAST(storeLongitude as double) * PI() / 180 - CAST(" +
                        "(CASE udfTimePeriod(couponId)\n" +
                        "WHEN '0' THEN lng\n" +
                        "WHEN '1' THEN lng_night\n" +
                        "ELSE lng_night \n" +
                        "END) " +
                        " as double) * PI() / 180) / 2), 2)\n" +
                        ")) * 1000) < storeRange limit 10000" +
                        ") t on a.couponId=t.couponId and " +
                "a.storeId=t.storeId and " +
                "a.storeRange=t.storeRange and " +
                "a.userNum=t.userNum and " +
                        "a.uniqueReqId = t.uniqueReqId"
//                        + " and " +
//                        " partstart='20220210'"
        );

        /*
            建表语句中加了latest，在将表转成流的时候，报错：
            Exception in thread "main" java.lang.IllegalArgumentException:
            The only supported 'streaming-source.partition.include' is 'all' in hive table scan, but is 'latest'
         */

        //表转成流
        DataStream<Tuple2<Boolean, Row>> itemResultStream = tableEnv.toRetractStream(itemResultTable, Row.class);
//        itemResultStream.print();

        //结果的处理,转换成kafka输出格式
        SingleOutputStreamOperator<CouponOutputMsg> itemResultOutputStream = itemResultStream.map(new MapFunction<Tuple2<Boolean, Row>, CouponOutputMsg>() {
            @Override
            public CouponOutputMsg map(Tuple2<Boolean, Row> booleanRowTuple2) throws Exception {

                //cert_type, cert_nbr, couponId, storeId, storeRange, userNum
                String rowToString = booleanRowTuple2.f1.toString();
                //输出结果：rowToString: 4,44444,347caf17-7f6d-41b7-9ba5-b3b1f49f5ea6,888999,500,null  并不是按照json输出
//                System.out.println("rowToString: " + rowToString);
                CouponOutputMsg couponOutputMsg = new CouponOutputMsg();
                couponOutputMsg.setSERIAL_NO(UUID.randomUUID().toString());
                couponOutputMsg.setID_TYPE((String) booleanRowTuple2.f1.getField(0));
                couponOutputMsg.setID_NUMBER((String) booleanRowTuple2.f1.getField(1));
                couponOutputMsg.setCOUPON_ID((String) booleanRowTuple2.f1.getField(2));
                couponOutputMsg.setSTORE_ID((String) booleanRowTuple2.f1.getField(3));
                couponOutputMsg.setSTORE_RANGE((String) booleanRowTuple2.f1.getField(4));
                couponOutputMsg.setCOUPON_SEND_NUM((String) booleanRowTuple2.f1.getField(5));
                couponOutputMsg.setUNIQUE_REQ_ID((String) booleanRowTuple2.f1.getField(6));
//                System.out.println("得到的CouponOutputMsg:" + JacksonUtils.toJSONString(couponOutputMsg));
                return couponOutputMsg;
            }
        });
//        itemResultOutputStream.print("itemResultOutputStream");

        SingleOutputStreamOperator<ResultWrapVO> itemResultOutputStream2 = itemResultOutputStream.keyBy(new KeySelector<CouponOutputMsg, String>() {
            @Override
            public String getKey(CouponOutputMsg couponOutputMsg) throws Exception {

                return StringUtils.join(new String[]{
                        couponOutputMsg.getCOUPON_ID(),
                        couponOutputMsg.getSTORE_ID(),
                        couponOutputMsg.getSTORE_RANGE(),
                        couponOutputMsg.getCOUPON_SEND_NUM(),
                        couponOutputMsg.getUNIQUE_REQ_ID(),
                }, "_");
            }
        }).window(ProcessingTimeSessionWindows.withGap(Time.seconds(2)))
                .apply(new WindowFunction<CouponOutputMsg, ResultWrapVO, String, TimeWindow>() {
                    @Override
                    public void apply(String s, TimeWindow window, Iterable<CouponOutputMsg> input, Collector<ResultWrapVO> out) throws Exception {

                        ResultWrapVO resultWrapVO = new ResultWrapVO();
                        Iterator iterator = input.iterator();
                        List<CouponOutputMsg> itemList = new ArrayList<>();
                        while (iterator.hasNext()){
                            CouponOutputMsg couponOutputMsg = (CouponOutputMsg) iterator.next();
                            if (StringUtils.isNotEmpty(couponOutputMsg.getID_TYPE()) && StringUtils.isNotEmpty(couponOutputMsg.getID_NUMBER())){
                                itemList.add(couponOutputMsg);
                            }
                        }
                        resultWrapVO.setItemList(itemList);
                        out.collect(resultWrapVO);
                    }
                });

        SingleOutputStreamOperator<CouponOutputMsg> itemResultOutputStream3 = itemResultOutputStream2.process(new ProcessFunction<ResultWrapVO, CouponOutputMsg>() {
            @Override
            public void processElement(ResultWrapVO value, Context ctx, Collector<CouponOutputMsg> out) throws Exception {
                List<CouponOutputMsg> itemList = value.getItemList();
                if (itemList.size() > 0){
                    CouponOutputMsg outputMsg = new CouponOutputMsg();
                    outputMsg.setMESSAGE_TYPE("01");
                    outputMsg.setCOUPON_SEND_NUM(String.valueOf(itemList.size()));
                    out.collect(outputMsg);
                    itemList.forEach(item->{
                        item.setMESSAGE_TYPE("02");
                        item.setCOUPON_SEND_NUM(String.valueOf(itemList.size()));
                        out.collect(item);
                    });
                    CouponOutputMsg outputMsg3 = new CouponOutputMsg();
                    outputMsg3.setMESSAGE_TYPE("03");
                    outputMsg3.setCOUPON_SEND_NUM(String.valueOf(itemList.size()));
                    out.collect(outputMsg3);
                }else {
                    CouponOutputMsg outputMsg = new CouponOutputMsg();
                    outputMsg.setMESSAGE_TYPE("01");
                    outputMsg.setCOUPON_SEND_NUM("0");
                    out.collect(outputMsg);

                }

            }
        });
        itemResultOutputStream3.print("itemResultOutputStream3");

        //输出源
        FlinkKafkaProducer flinkKafkaProducer = FlinkKafkaConfig.getFlinkKafkaProducer("coupon-output");
//        itemResultOutputStream.addSink(flinkKafkaProducer);

        System.out.println("开始执行UserCouponMatchingTask6");
        env.execute();
    }

    static class ResultWrapVO {

        private List<CouponOutputMsg> itemList;

        public List<CouponOutputMsg> getItemList() {
            return itemList;
        }

        public void setItemList(List<CouponOutputMsg> itemList) {
            this.itemList = itemList;
        }
    }


}
