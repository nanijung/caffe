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

결제서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현
# (app) 결제이력Service.java

package caffe.external;

@FeignClient(name="pay", url="http://localhost:8082")//, fallback = 결제이력ServiceFallback.class)
public interface 결제이력Service {

    @RequestMapping(method= RequestMethod.POST, path="/결제이력s")
    public void 결제(@RequestBody 결제이력 pay);

}
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
동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문도 못받는다는 것을 확인:
# 결제 (payment) 서비스를 잠시 내려놓음 
kubectl scale deploy payment --replicas=0 -n project

#주문처리
kubectl exec -it httpie -c httpie  -n project -- /bin/bash
http POST http://order:8080/orders  
http POST http://order:8080/orders menuId=1 qty=1 status="Ordered" 

#결제서비스 재기동
cd 결제
mvn spring-boot:run

#주문처리
http localhost:8081/orders item=통닭 storeId=1   #Success
http localhost:8081/orders item=피자 storeId=2   #Success
