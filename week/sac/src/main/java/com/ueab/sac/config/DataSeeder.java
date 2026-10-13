package com.ueab.sac.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ueab.sac.model.Course;
import com.ueab.sac.model.Role;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.UserRepository;

/** Seeds demo courses + an admin account on first run. */
@Configuration
public class DataSeeder {

    @Bean
    ApplicationRunner seed(CourseRepository courses, UserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (courses.count() == 0) {
                courses.save(new Course("BIT 2104", "Introduction to Programming", "Mr. Otieno"));
                courses.save(new Course("BIT 2105", "Database Systems", "Mrs. Wanjiru"));
                courses.save(new Course("BIT 2201", "Data Structures & Algorithms", "Dr. Kamau"));
                courses.save(new Course("BIT 2210", "Systems Analysis & Design", "Mr. Mutiso"));
            }
            if (users.findByUsernameIgnoreCase("admin").isEmpty()) {
                users.save(new User("admin", "System Administrator", "admin@ueab.ac.ke",
                        encoder.encode("admin123"), Role.ADMIN));
            }
        };
    }
}
