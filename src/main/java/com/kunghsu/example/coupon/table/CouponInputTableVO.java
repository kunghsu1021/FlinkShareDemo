package com.kunghsu.example.coupon.table;

public class CouponInputTableVO {

    private String couponId;
    private String storeRange;
    private String storeLongitude;
    private String storeLatitude;
    private String userNum;
    private String messageType;
    private String storeId;
    private String uniqueReqId;

    public String getCouponId() {
        return couponId;
    }

    public void setCouponId(String couponId) {
        this.couponId = couponId;
    }

    public String getStoreRange() {
        return storeRange;
    }

    public void setStoreRange(String storeRange) {
        this.storeRange = storeRange;
    }

    public String getStoreLongitude() {
        return storeLongitude;
    }

    public void setStoreLongitude(String storeLongitude) {
        this.storeLongitude = storeLongitude;
    }

    public String getStoreLatitude() {
        return storeLatitude;
    }

    public void setStoreLatitude(String storeLatitude) {
        this.storeLatitude = storeLatitude;
    }

    public String getUserNum() {
        return userNum;
    }

    public void setUserNum(String userNum) {
        this.userNum = userNum;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getUniqueReqId() {
        return uniqueReqId;
    }

    public void setUniqueReqId(String uniqueReqId) {
        this.uniqueReqId = uniqueReqId;
    }

    @Override
    public String toString() {
        return "CouponInputTableVO{" +
                "couponId='" + couponId + '\'' +
                ", storeRange='" + storeRange + '\'' +
                ", storeLongitude='" + storeLongitude + '\'' +
                ", storeLatitude='" + storeLatitude + '\'' +
                ", userNum='" + userNum + '\'' +
                ", messageType='" + messageType + '\'' +
                ", storeId='" + storeId + '\'' +
                ", uniqueReqId='" + uniqueReqId + '\'' +
                '}';
    }
}
