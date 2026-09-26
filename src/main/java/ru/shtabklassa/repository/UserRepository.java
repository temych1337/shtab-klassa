package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByExternalId(String externalId);

    List<User> findByClassIdAndRoleOrderByFullName(Long classId, Role role);

    long countByClassIdAndRole(Long classId, Role role);

    @Query("""
            select u from User u
            where u.classId = :classId and u.role = ru.shtabklassa.model.Role.PARENT
              and not exists (select 1 from AnnouncementRead r where r.announcementId = :announcementId and r.userId = u.id)
            order by u.fullName""")
    List<User> findSilentParentsForAnnouncement(@Param("classId") Long classId, @Param("announcementId") Long announcementId);

    @Query("""
            select u from User u
            where u.classId = :classId and u.role = ru.shtabklassa.model.Role.PARENT
              and not exists (select 1 from PollAnswer a where a.pollId = :pollId and a.userId = u.id)
            order by u.fullName""")
    List<User> findSilentParentsForPoll(@Param("classId") Long classId, @Param("pollId") Long pollId);
}
