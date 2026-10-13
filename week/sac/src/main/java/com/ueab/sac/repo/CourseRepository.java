package com.ueab.sac.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ueab.sac.model.Course;

public interface CourseRepository extends JpaRepository<Course, Long> {
    Optional<Course> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
}
