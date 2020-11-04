커피 주문 시스템

본 프로그램은 택배시스템이다.

서비스 시나리오
기능적 요구사항
1. 고객이 메뉴, 수량를 선택하여 주문한다.
2. 주문을 하면 결제 기능이 호출된다.
3. 주문이 되면 주문 내역이 바리스타에게 전달된다.
4. 바리스탁가 확인하여 커피를 만든다.
5. 고객이 주문을 취소할 수 있다
6. 주문이 취소되면 커피 제작이 취소된다
7. 고객이 주문상태를 중간중간 조회한다
비기능적 요구사항
트랜잭션
1. 결제가 되지 않은 주문건은 아예 거래가 성립되지 않아야 한다  Sync 호출
장애격리
2. 주문은 365일 24시간 받을 수 있어야 한다  Async (event-driven), Eventual Consistency
3. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다  Circuit breaker
4 고객이 자주 상점관리에서 확인할 수 있는 배달상태를 주문시스템(프론트엔드)에서 확인할 수 있어야 한다  CQRS

분석/설계
Event Storming 결과
MSAEz 로 모델링한 이벤트스토밍 결과:

동기식 호출 과 Fallback 처리
분석단계에서의 조건 중 하나로 주문(app)->결제(pay) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

결제서비스를 호출하기 위하여 FeignClient 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현
# (app) 결제이력Service.java

@FeignClient(name="payment", url="${api.payment.url}")
public interface PaymentService {

    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void pay(@RequestBody Payment payment);

}
주문을 받은 직후(@PostPersist) 결제를 요청하도록 처리
# PaymentService.java

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
동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문도 못받는다는 것을 확인:
# 결제 (payment) 서비스를 잠시 내려놓음 
kubectl scale deploy payment --replicas=0 -n project

#주문처리
kubectl exec -it httpie -c httpie  -n project -- /bin/bash
http POST http://order:8080/orders menuId=1 qty=1 status="Ordered" # 500에러

#결제서비스 재기동
kubectl scale deploy payment --replicas=1 -n project

#주문처리
http POST http://order:8080/orders menuId=1 qty=1 status="Ordered" #성공



비동기식 호출 / 시간적 디커플링 / 장애격리 / 최종 (Eventual) 일관성 테스트
결제가 이루어진 후에 상점시스템으로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리하여 상점 시스템의 처리를 위하여 결제주문이 블로킹 되지 않아도록 처리한다.

이를 위하여 결제이력에 기록을 남긴 후에 곧바로 결제승인이 되었다는 도메인 이벤트를 카프카로 송출한다(Publish)
@Entity
@Table(name="Payment_table")
public class Payment {
    @PostPersist
    public void onPostPersist(){
        Paid paid = new Paid();
        BeanUtils.copyProperties(this, paid);
        paid.publishAfterCommit();
    }
제조 서비스에서는 결제승인 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:
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
            Make make = new Make();
            make.setOrderId(paid.getOrderId());
            make.setStatus("CoffeeServed");

            makeRepository.save(make);
        }

제조 시스템은 주문/결제와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, 제조 시스템이 유지보수로 인해 잠시 내려간 상태라도 주문을 받는데 문제가 없다:

# 제조 서비스 (makecoffee) 를 잠시 내려놓음
 kubectl scale deploy makecoffee --replicas=0 -n project

#주문처리
  http POST http://20.196.145.178:8080/orders menuId=1 qty=1 status="Ordered" #성공
  http POST http://20.196.145.178:8080/orders menuId=2 qty=2 status="Ordered" #성공

#주문상태 확인
http http://20.196.145.178:8080/orders     # 주문상태 안바뀜 확인

#상점 서비스 기동
 kubectl scale deploy makecoffee --replicas=1 -n project

#주문상태 확인
http http://20.196.145.178:8080/orders    # 모든 주문의 상태가 "CoffeeServed"으로 확인

동기식 호출 / 서킷 브레이킹 / 장애격리
서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Istio 옵션을 사용하여 구현함
시나리오는 주문-->결제 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 결제 요청이 과도할 경우 CB 를 통하여 장애격리.
destination rule에 설정.

kubectl apply -f - <<EOF
apiVersion: networking.istio.io/v1alpha3
kind: DestinationRule
metadata:
  name: dr-payment
  namespace: project
spec:
  host: payment
  trafficPolicy:
    connectionPool:
      http:
        http1MaxPendingRequests: 1
        maxRequestsPerConnection: 3
    outlierDetection:
      interval: 5s
      consecutiveErrors: 1
      baseEjectionTime: 5m
      maxEjectionPercent: 100
EOF

피호출 서비스(결제:payment) 의 임의 부하 처리 
# Order.java

    @PrePersist
    public void onPrePersist(){  //결제이력을 저장한 후 적당한 시간 끌기

        ...
        
        try {
            Thread.currentThread().sleep((long) (800 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:
동시사용자 1명 10초 동안 실시
siege -c1- -t10s -v hppt://payment:8080/payments

[error] UNSPPRTD 501   0.00 secs:       0 bytes ==> PROTOCOL NOT SUPPORTED BY SIEGE

[error] UNSPPRTD 501   0.00 secs:       0 bytes ==> PROTOCOL NOT SUPPORTED BY SIEGE

[error] UNSPPRTD 501   0.00 secs:       0 bytes ==> PROTOCOL NOT SUPPORTED BY SIEGE

[error] UNSPPRTD 501   0.00 secs:       0 bytes ==> PROTOCOL NOT SUPPORTED BY SIEGE

siege aborted due to excessive socket failure; you
can change the failure threshold in $HOME/.siegerc

Transactions:                      0 hits
Availability:                   0.00 %
Elapsed time:                   0.02 secs
Data transferred:               0.00 MB
Response time:                  0.00 secs
Transaction rate:               0.00 trans/sec
Throughput:                     0.00 MB/sec
Concurrency:                    0.00
Successful transactions:           0
Failed transactions:            1024
Longest transaction:            0.00
Shortest transaction:           0.00

kubectl exec -it httpie -c httpie  -n project -- /bin/bash

root@httpie:/# http http://order:8080/payments 
HTTP/1.1 404 Not Found
content-type: application/hal+json;charset=UTF-8
date: Wed, 04 Nov 2020 14:29:54 GMT
server: envoy
transfer-encoding: chunked
x-envoy-upstream-service-time: 38

{
    "error": "Not Found", 
    "message": "No message available", 
    "path": "/payments", 
    "status": 404, 
    "timestamp": "2020-11-04T14:29:54.881+0000"
}
