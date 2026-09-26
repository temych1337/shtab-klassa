package ru.shtabklassa.service;

import org.springframework.stereotype.Service;
import ru.shtabklassa.model.ClassEntity;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.ClassRepository;
import ru.shtabklassa.repository.UserRepository;

import java.util.Optional;

@Service
public class UserService {

    public record ClassContext(String className, String teacherName) {
    }

    private final UserRepository users;
    private final ClassRepository classes;

    public UserService(UserRepository users, ClassRepository classes) {
        this.users = users;
        this.classes = classes;
    }

    public Optional<User> findByExternalId(String externalId) {
        return users.findByExternalId(externalId);
    }

    public Optional<ClassContext> classContext(User user) {
        if (user.getClassId() == null) {
            return Optional.empty();
        }
        return classes.findById(user.getClassId()).map(klass -> new ClassContext(klass.getName(),
                Optional.ofNullable(klass.getTeacherId()).flatMap(users::findById).map(User::getFullName).orElse(null)));
    }
}
