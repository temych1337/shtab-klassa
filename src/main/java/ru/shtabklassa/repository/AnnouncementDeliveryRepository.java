package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.shtabklassa.model.AnnouncementDelivery;

import java.util.List;

public interface AnnouncementDeliveryRepository extends JpaRepository<AnnouncementDelivery, AnnouncementDelivery.Key> {

    long countByAnnouncementIdAndFailedReasonIsNotNull(Long announcementId);

    long countByAnnouncementIdAndFailedReason(Long announcementId, String failedReason);

    List<AnnouncementDelivery> findByAnnouncementId(Long announcementId);
}
