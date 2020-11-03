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
    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPayCanceled_Makecancel(@Payload PayCanceled payCanceled){

        try {
            if (payCanceled.isMe()) {
                //    System.out.println("##### listener Paycancel : " + payCanceled.toJson());
                List<Make> makeList = makeRepository.findByOrderId(payCanceled.getOrderId());
                for(Make make : makeList){
                    make.setStatus("MakeCanceled");
                    makeRepository.save(make);
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}
