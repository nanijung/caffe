package caffe;

import org.springframework.data.repository.PagingAndSortingRepository;
import java.util.List;

public interface MakeRepository extends PagingAndSortingRepository<Make, Long>{
    List<Make> findByOrderId(Long orderId);

}