package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.shtabklassa.model.ClassEntity;

import java.util.Optional;

public interface ClassRepository extends JpaRepository<ClassEntity, Long> {

    Optional<ClassEntity> findByName(String name);
}
