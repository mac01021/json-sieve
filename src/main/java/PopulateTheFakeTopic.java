import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

/**
 * Created by coolbetm on 1/30/16.
 */
public class PopulateTheFakeTopic {
    public static void main(String[] args) throws Exception {
        KafkaProducer<Void, String> producer = new KafkaProducer<Void, String>(new HashMap<String, Object>(){{
            put("bootstrap.servers", "dp-dev-kb0-metadata.dev.dp.pvt:9092");
            put("key.serializer", StringSerializer.class);
            put("value.serializer", StringSerializer.class);
        }});
        for (int i= 0; i < 2000000; i ++) {
            String event = String.format("{ \"time\": %d, \"zip\": { \"foo\": \"%s\", \"bar\":\"%s\" } }", new Date().getTime(),
                    UUID.randomUUID(), UUID.randomUUID());
            ProducerRecord<Void, String> record = new ProducerRecord<Void, String>("dp-coolbetm-fake-log-topic", null, event);
            producer.send(record);
            System.out.println(event);
            Thread.sleep(2000);
        }

    }
}




