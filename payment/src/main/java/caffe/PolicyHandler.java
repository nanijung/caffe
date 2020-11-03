package caffe;

import caffe.config.kafka.KafkaProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PolicyHandler{
    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }

    @Autowired
    PaymentRepository paymentRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverOrderCanceled_Paycancel(@Payload OrderCanceled orderCanceled){

        try {
            if (orderCanceled.isMe()) {
                System.out.println("##### listener Paycancel : " + orderCanceled.toJson());
                List<Payment> paymentList = paymentRepository.findByOrderId(orderCanceled.getId());
                for(Payment payment : paymentList){
                    payment.setStatus("PayCanceled");
                    paymentRepository.save(payment);
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}
