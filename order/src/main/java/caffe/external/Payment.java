package caffe.external;

public class Payment {

    private Long id;
    private Long orderId;
    private Long chargeAmount;
    private String status;

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public Long getOrderId() {
        return orderId;
    }
    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }
    public Long getChargeAmount() {
        return chargeAmount;
    }
    public void setChargeAmount(Long chargeAmount) {
        this.chargeAmount = chargeAmount;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

}
