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
