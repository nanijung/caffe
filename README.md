# 커피 주문 시스템


## 기능적 요구사항
1. 고객이 메뉴, 수량를 선택하여 주문한다.
2. 주문을 하면 결제 기능이 호출된다.
3. 주문이 되면 주문 내역이 바리스타에게 전달된다.
4. 바리스타가 확인하여 커피를 만든다.
5. 고객이 주문을 취소할 수 있다
6. 주문이 취소되면 커피 제작이 취소된다
7. 고객이 주문상태를 중간중간 조회한다

## 비기능적 요구사항
1. 결제가 되지 않은 주문건은 아예 거래가 성립되지 않아야 한다 
2. 주문은 365일 24시간 받을 수 있어야 한다 
3. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다  
4. 고객이 주문 상태를 시스템에서 확인할 수 있어야 한다  

# 분석/설계
## Event Storming 결과
* MSAEz 로 모델링한 이벤트스토밍 결과: http://www.msaez.io/#/storming/QDfI806oeNaYRhJgglhwzluA0kf2/mine/c3f08b5731b9a89afa6c3c110b0e4a12/-MLDcTKVYfbZc5OksFOf
이벤트 도출

![image](https://user-images.githubusercontent.com/70181652/98206405-2d850f00-1f7d-11eb-8679-f6982dfb93d4.png)
```
    - 도메인 서열 분리 
    - Core Domain:  Order : caffe 핵심 서비스이며, 연간 Up-time SLA 수준을 99.999% 목표, 배포 주기는 Order 의 경우 1주일 1회 미만
    - Supporting Domain:   make : 경쟁력을 내기 위한 서비스이며, SLA 수준은 연간 60% 이상 uptime 목표, 배포주기는 각 팀의 자율이나 표준 스프린트 주기가 1주일 이므로 1주일 1회 이상을 기준으로 함.
    - General Domain:   Payment : 결제서비스로 3rd Party 외부 서비스를 사용하는 것이 경쟁력이 높음
```
## 헥사고날 아키텍처 다이어그램 도출
![image](https://user-images.githubusercontent.com/70181652/98321806-54008400-2029-11eb-8a3e-d41f6fb16cff.png)
```
- 이벤트 흐름에서 Inbound adaptor와 Outbound adaptor를 구분함
- 호출 관계에서 Pub/Sub 과 Req/Resp 를 구분함
- 바운디드 컨텍스트에 서브 도메인을 1 대 1 모델링하고 팀원별 관심 구현 스토리를 나눠가짐
```
    
# 구현:
* 분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 Bounded Context 별로 대변되는 마이크로 서비스들을 Spring Boot 로 구현하였다. 
구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다. (포트 넘버는 8081 ~ 8084 이다)
```
cd Order
mvn spring-boot:run

cd Payment
mvn spring-boot:run 

cd Delivery
mvn spring-boot:run  

cd customerview
mvn spring-boot:run 
```
## DDD 의 적용
* 각 서비스내에 도출된 핵심 Aggregate Root 객체를 Entity 로 선언하였다: (예시는 Order 마이크로 서비스)
```java
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
```

* 적용 후 REST API 의 테스트
```
# Order 서비스의 주문처리
http POST http://20.196.136.114:8080/orders menuId=1 qty=1 status="Ordered"
```
![image](https://user-images.githubusercontent.com/70181652/98207012-5528a700-1f7e-11eb-94c1-eb93bf466c37.png)

```
# Order 서비스의 취소처리
http PATCH http://20.196.136.114:8080/orders/2 status="OrderCanceled"
```
![image](https://user-images.githubusercontent.com/70181652/98207089-77bac000-1f7e-11eb-9f70-478f6d88308a.png)
  
```
# Order 서비스의 주문 상태 확인
http GET http://20.196.136.114:8080/orders
```
![image](https://user-images.githubusercontent.com/70181652/98207135-8ef9ad80-1f7e-11eb-9fcc-a5f3bbefd930.png)

## 폴리글랏 퍼시스턴스
제조서비스에는 H2 DB 대신 HSQLDB를 사용하기로 하였다. 이를 위해 메이븐 설정(pom.xml)상 DB 정보를 HSQLDB를 사용하도록 변경하였다.
![image](https://user-images.githubusercontent.com/70181652/98315720-3678ed80-201c-11eb-8743-791a76ec7fcc.png)
![image](https://user-images.githubusercontent.com/70181652/98317906-c6b93180-2020-11eb-9b82-e8b2d2e0f234.png)
![image](https://user-images.githubusercontent.com/70181652/98315844-7dff7980-201c-11eb-9e0c-32be634a591f.png)
  
## 동기식 호출 과 Fallback 처리
주문(order)->결제(payment) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

* 결제서비스를 호출하기 위하여 FeignClient 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현
```
# (Order) PaymentService.java

@FeignClient(name="payment", url="${api.payment.url}")
public interface PaymentService {

    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void pay(@RequestBody Payment payment);
```
* 주문을 받은 직후(@PostPersist) 결제를 요청하도록 처리
```
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
 ```
* 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문도 못받는다는 것을 확인 
```
# 결제 (Payment) 서비스를 잠시 내려놓음 
kubectl scale deploy payment --replicas=0 -n project
```
```
# 주문처리
http POST http://20.196.136.114:8080/orders menuId=4 qty=4 status="Ordered"   #Fail
```
![image](https://user-images.githubusercontent.com/70181652/98208140-26133500-1f80-11eb-9550-5f2e00b85a43.png)
```  
# 결제서비스 재기동
kubectl scale deploy payment --replicas=1 -n project
```
```
# 주문처리
http POST http://20.196.136.114:8080/orders menuId=4 qty=4 status="Ordered" #Success
```

![image](https://user-images.githubusercontent.com/70181652/98208310-6d99c100-1f80-11eb-8535-21256d6a0ca9.png)
  
## 비동기식 호출 / 시간적 디커플링 / 장애격리 / 최종 (Eventual) 일관성 테스트
결제가 이루어진 후에 배송서비스로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리하여 배송서비스의 처리를 위하여 결제주문이 블로킹 되지 않도록 처리한다.

* 이를 위하여 결제이력에 기록을 남긴 후에 곧바로 결제승인이 되었다는 도메인 이벤트를 카프카로 송출한다(Publish)
```java
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
  ```
  * 제조서비스에서는 결제승인 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:
```java
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
```
![image](https://user-images.githubusercontent.com/70181652/98210562-1564be00-1f84-11eb-8aab-e334233bd42a.png)

```
# 제조서비스 기동
kubectl scale deploy makecoffee --replicas=1 -n project

# 주문상태 확인
http GET http://20.196.136.114:8080/mypages     # 주문의 상태가 "CoffeeServed"으로 확인
```
![image](https://user-images.githubusercontent.com/70181652/98210931-ad62a780-1f84-11eb-8d4c-8525a7780eab.png)

## CQRS
mypage를 통해 구현하였다.

![image](https://user-images.githubusercontent.com/70181652/98211172-ff0b3200-1f84-11eb-9cb4-d41345b1b76c.png)

## gateway
gateway 프로젝트 내 application.yml

![image](https://user-images.githubusercontent.com/70181652/98211494-7a6ce380-1f85-11eb-9950-28638fcf9868.png)
![image](https://user-images.githubusercontent.com/70181652/98211558-8e184a00-1f85-11eb-9909-1109480dbbae.png)

![image](https://user-images.githubusercontent.com/70181652/98211694-c28c0600-1f85-11eb-8959-9003cc98402e.png)

# 운영

## Circuit Breaker 점검
```
호출 서비스(주문:order) 임의 부하 처리 - 800 밀리에서 증감 220 밀리 정도 왔다 갔다 하게
# Order.java (Entity)

    public void onPrePersist(){
        try {
            Thread.currentThread().sleep((long) (800 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
 ```
 ## 부하 발생을 통한 Circuit Breaker 점검
![image](https://user-images.githubusercontent.com/70181652/98214624-126ccc00-1f8a-11eb-9f35-b8221e877578.png)

![image](https://user-images.githubusercontent.com/70181652/98214684-26183280-1f8a-11eb-9707-d3de8efc4c23.png)



## 오토스케일 아웃
Circuite Breaker 는 시스템을 안정되게 운영할 수 있게 해줬지만, 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다.

* 결제서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 20프로를 넘어서면 replica 를 20개까지 늘려준다:
```
kubectl autoscale deploy payment --cpu-percent=15 --min=1 --max=10 -n project
```
* Circuite Breaker 에서 했던 방식대로 워크로드를 2분 동안 걸어준다.
```
siege -c100 -t120S -v --content-type "application/json" 'http://order:8080/orders POST {"menuId":1, "qty":1}'
```
* 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:
```
kubectl get deploy payment -w
```
* 어느 정도 시간이 흐른 후 (약 30초) 스케일 아웃이 벌어지는 것을 확인할 수 있다:
![image](https://user-images.githubusercontent.com/70181652/98245625-8b811900-1fb4-11eb-809d-493b1f3c8bf8.png)
![image](https://user-images.githubusercontent.com/70181652/98250054-57105b80-1fba-11eb-9a7f-3990669aafa3.png)

* siege 의 로그를 보아도 전체적인 성공률이 높아진 것을 확인 할 수 있다.
![image](https://user-images.githubusercontent.com/70181652/98254645-e409e380-1fbf-11eb-96f2-d9337bc17b5f.png)


## Liveness Probe 점검

### 파일 상태 점검

5초 간격으로 특정 위치의 파일 생성 여부를 확인하고, 없으면 실패로 인식해서 프로세스를 Kill하고 다시 시작, 일정 시간 (30초)가 지나면 다시 파일을 삭제하고 Liveness 를 위한 서비스 수행한다.

### 설정 확인
```
apiVersion: v1
kind: Pod
metadata:
  labels:
    test: orderLiveness
  name: order
  namespace: project
spec:
  containers:
  - name: order
    image: nanijung.azurecr.io/order:v1
    args:
    - /bin/sh
    - -c
    - touch /tmp/healthy; sleep 30; rm -rf /tmp/healthy; sleep 600
    livenessProbe:
      exec:
        command:
        - cat
        - /tmp/healthy
      initialDelaySeconds: 5
      periodSeconds: 5
```
liveness 적용된 pod 생성
```
kubectl create -f exec-liveness.yaml
```
liveness 적용된 order pod 의 상태 체크( 테스트 결과 )
```
kubectl describe po order -n project
```
![image](https://user-images.githubusercontent.com/70181652/98242909-7d30fe00-1fb0-11eb-8229-3f6a37373b3b.png)


## Config Map
```
Order 서비스에 configmap.yml 파일을 생성한다.

apiVersion: v1
kind: ConfigMap
metadata:
  name: apiurl
data:
  url: http://payment:8080
  fluented-sever-ip: 10.xxx.xxx.xxx
```
```
Order 서버스의 deployment.yml에 configmap 파일을 참조할 수 있는 값을 추가한다.

          env:
            - name: configurl
              valueFrom:
                configMapKeyRef:
                  name: apiurl
                  key: url
```
```
Order 서버스의 apllication.yml에 deployment에 추가된 값을 참조하도록 추가한다.

api:
  payment:
    url: ${configurl}
```
```
Order 서버스의 PaymentService.java에 외부 값을 보도록 변경한다.

@FeignClient(name="Payment", url="${api.payment.url}")
public interface PaymentService {

    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void payReq(@RequestBody Payment payment);

}
```
```
order 서비스의 호출 시 에러발생.
```
![image](https://user-images.githubusercontent.com/70181652/98321460-7645d200-2028-11eb-835b-e8b1f38324a1.png)




