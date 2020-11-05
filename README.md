커피 주문 시스템


서비스 시나리오
분석/설계
구현:
DDD 의 적용
동기식 호출 과 Fallback 처리
비동기식 호출 과 Eventual Consistency
CQRS
gateway
운영
동기식 호출 / 서킷 브레이킹 / 장애격리
오토스케일 아웃
무정지 재배포
Liveness
Config Map
서비스 시나리오

기능적 요구사항
1. 고객이 메뉴, 수량를 선택하여 주문한다.
2. 주문을 하면 결제 기능이 호출된다.
3. 주문이 되면 주문 내역이 바리스타에게 전달된다.
4. 바리스타가 확인하여 커피를 만든다.
5. 고객이 주문을 취소할 수 있다
6. 주문이 취소되면 커피 제작이 취소된다
7. 고객이 주문상태를 중간중간 조회한다

비기능적 요구사항
트랜잭션
1. 결제가 되지 않은 주문건은 아예 거래가 성립되지 않아야 한다 
장애격리
2. 주문은 365일 24시간 받을 수 있어야 한다 
3. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다  
4 고객이 주문 상태를 시스템에서 확인할 수 있어야 한다  

분석/설계
Event Storming 결과
MSAEz 로 모델링한 이벤트스토밍 결과: http://www.msaez.io/#/storming/QDfI806oeNaYRhJgglhwzluA0kf2/mine/c3f08b5731b9a89afa6c3c110b0e4a12/-MLDcTKVYfbZc5OksFOf
이벤트 도출

![image](https://user-images.githubusercontent.com/70181652/98206405-2d850f00-1f7d-11eb-8679-f6982dfb93d4.png)

- 도메인 서열 분리 
    - Core Domain:  Order : caffe 핵심 서비스이며, 연간 Up-time SLA 수준을 99.999% 목표, 배포 주기는 Order 의 경우 1주일 1회 미만
    - Supporting Domain:   make : 경쟁력을 내기 위한 서비스이며, SLA 수준은 연간 60% 이상 uptime 목표, 배포주기는 각 팀의 자율이나 표준 스프린트 주기가 1주일 이므로 1주일 1회 이상을 기준으로 함.
    - General Domain:   Payment : 결제서비스로 3rd Party 외부 서비스를 사용하는 것이 경쟁력이 높음
    
    
    구현:
분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 Bounded Context 별로 대변되는 마이크로 서비스들을 Spring Boot 로 구현하였다. 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다. (포트 넘버는 8081 ~ 8084 이다)

cd Order
mvn spring-boot:run

cd Payment
mvn spring-boot:run 

cd Delivery
mvn spring-boot:run  

cd customerview
mvn spring-boot:run 

DDD 의 적용
각 서비스내에 도출된 핵심 Aggregate Root 객체를 Entity 로 선언하였다: (예시는 Order 마이크로 서비스)
package caffe;

import javax.persistence.*;

import caffe.external.Payment;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Order_table")
public class Order {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long menuId;
    private Long qty;
    private String status;

    @PostUpdate
    public void onPostUpdate(){
        OrderCanceled orderCanceled = new OrderCanceled();
        BeanUtils.copyProperties(this, orderCanceled);
        orderCanceled.publishAfterCommit();
    }

    @PostPersist
    public void onPostPersist(){
        Ordered ordered = new Ordered();
        BeanUtils.copyProperties(this, ordered);
        ordered.publishAfterCommit();

        caffe.external.Payment payment = new caffe.external.Payment();
        // mappings goes here
        payment.setOrderId(ordered.getId());
        payment.setChargeAmount(ordered.getQty()*100);
        payment.setStatus("Paid");

        OrderApplication.applicationContext.getBean(caffe.external.PaymentService.class)
            .pay(payment);
    }
    @PrePersist
    public void onPrePersist(){
        try {
            Thread.currentThread().sleep((long) (800 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    // getter(), setter() 중략
}

적용 후 REST API 의 테스트
# Order 서비스의 주문처리
http POST http://20.196.136.114:8080/orders menuId=1 qty=1 status="Ordered"
![image](https://user-images.githubusercontent.com/70181652/98207012-5528a700-1f7e-11eb-94c1-eb93bf466c37.png)

# Order 서비스의 취소처리
http PATCH http://20.196.136.114:8080/orders/2 status="OrderCanceled"
![image](https://user-images.githubusercontent.com/70181652/98207089-77bac000-1f7e-11eb-9f70-478f6d88308a.png)
  
# Order 서비스의 주문 상태 확인
http GET http://20.196.136.114:8080/orders
![image](https://user-images.githubusercontent.com/70181652/98207135-8ef9ad80-1f7e-11eb-9fcc-a5f3bbefd930.png)
  
동기식 호출 과 Fallback 처리
주문(order)->결제(payment) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

결제서비스를 호출하기 위하여 FeignClient 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현

# (Order) PaymentService.java

@FeignClient(name="payment", url="${api.payment.url}")
public interface PaymentService {

    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void pay(@RequestBody Payment payment);

주문을 받은 직후(@PostPersist) 결제를 요청하도록 처리
# Order.java (Entity)
    @PostPersist
    public void onPostPersist(){
        Ordered ordered = new Ordered();
        BeanUtils.copyProperties(this, ordered);
        ordered.publishAfterCommit();

        caffe.external.Payment payment = new caffe.external.Payment();
 
        payment.setOrderId(ordered.getId());
        payment.setChargeAmount(ordered.getQty()*100);
        payment.setStatus("Paid");

        OrderApplication.applicationContext.getBean(caffe.external.PaymentService.class)
            .pay(payment);
    }
동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문도 못받는다는 것을 확인 
# 결제 (Payment) 서비스를 잠시 내려놓음 
kubectl scale deploy payment --replicas=0 -n project

# 주문처리
http POST http://20.196.136.114:8080/orders menuId=4 qty=4 status="Ordered"   #Fail
![image](https://user-images.githubusercontent.com/70181652/98208140-26133500-1f80-11eb-9550-5f2e00b85a43.png)
  
# 결제서비스 재기동
kubectl scale deploy payment --replicas=1 -n project

# 주문처리
http POST http://20.196.136.114:8080/orders menuId=4 qty=4 status="Ordered" #Success


![image](https://user-images.githubusercontent.com/70181652/98208310-6d99c100-1f80-11eb-8535-21256d6a0ca9.png)
  
비동기식 호출 / 시간적 디커플링 / 장애격리 / 최종 (Eventual) 일관성 테스트
결제가 이루어진 후에 배송서비스로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리하여 배송서비스의 처리를 위하여 결제주문이 블로킹 되지 않도록 처리한다.

이를 위하여 결제이력에 기록을 남긴 후에 곧바로 결제승인이 되었다는 도메인 이벤트를 카프카로 송출한다(Publish)
# Payment.java (Entity)

@Entity
@Table(name="Payment_table")
public class Payment {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long orderId;
    private Long chargeAmount;
    private String status;

    @PostPersist
    public void onPostPersist(){
        Paid paid = new Paid();
        BeanUtils.copyProperties(this, paid);
        paid.publishAfterCommit();


    }
  서비스에서는 결제승인 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

# (makecoffee) PolicyHandler.java

package caffe;

@Service
public class PolicyHandler{
    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }

    @Autowired
    MakeRepository makeRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPaid_Make(@Payload Paid paid){

        if(paid.isMe()){
           // System.out.println("##### listener Make : " + paid.toJson());

            Make make = new Make();
            make.setOrderId(paid.getOrderId());
            make.setStatus("CoffeeServed");

            makeRepository.save(make);
        }
    }
    
제조 서비스는 주문/결제와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, 제조 서비스가 유지보수로 인해 
잠시 내려간 상태라도 주문을 받는데 문제가 없다:

# 제조서비스 (makecoffee) 를 잠시 내려놓음 
kubectl scale deploy makecoffee --replicas=0 -n project

# 주문처리
http POST http://20.196.136.114:8080/orders menuId=5 qty=5 status="Ordered" #Success

# 주문상태 확인
http GET http://20.196.136.114:8080/mypages     # 주문상태 안바뀜 확인
![image](https://user-images.githubusercontent.com/70181652/98210562-1564be00-1f84-11eb-8aab-e334233bd42a.png)

# 제조서비스 기동
kubectl scale deploy makecoffee --replicas=1 -n project

# 주문상태 확인
http GET http://20.196.136.114:8080/mypages     # 주문의 상태가 "CoffeeServed"으로 확인
![image](https://user-images.githubusercontent.com/70181652/98210931-ad62a780-1f84-11eb-8d4c-8525a7780eab.png)

CQRS
mypage를 통해 구현하였다.

![image](https://user-images.githubusercontent.com/70181652/98211172-ff0b3200-1f84-11eb-9cb4-d41345b1b76c.png)

gateway
gateway 프로젝트 내 application.yml
![image](https://user-images.githubusercontent.com/70181652/98211494-7a6ce380-1f85-11eb-9950-28638fcf9868.png)
![image](https://user-images.githubusercontent.com/70181652/98211558-8e184a00-1f85-11eb-9909-1109480dbbae.png)

![image](https://user-images.githubusercontent.com/70181652/98211694-c28c0600-1f85-11eb-8959-9003cc98402e.png)

