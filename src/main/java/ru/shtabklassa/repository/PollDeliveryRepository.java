package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.shtabklassa.model.PollDelivery;

import java.util.List;

public interface PollDeliveryRepository extends JpaRepository<PollDelivery, PollDelivery.Key> {

    long countByPollIdAndFailedReasonIsNotNull(Long pollId);

    long countByPollIdAndFailedReason(Long pollId, String failedReason);

    List<PollDelivery> findByPollId(Long pollId);
}
