package caffe;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Make_table")
public class Make {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long orderId;
    private String status;

    @PostPersist
    public void onPostPersist() {
        CoffeeServed coffeeServed = new CoffeeServed();
        BeanUtils.copyProperties(this, coffeeServed);
        coffeeServed.publishAfterCommit();
    }

    @PostUpdate
    public void onPostUpdate(){
        MakeCanceled makeCanceled = new MakeCanceled();
        BeanUtils.copyProperties(this, makeCanceled);
        makeCanceled.publishAfterCommit();


    }


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
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }




}
