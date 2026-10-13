package com.ueab.sac.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ueab.sac.model.Enrollment;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    List<Enrollment> findByStudentId(Long studentId);

    List<Enrollment> findByCourseId(Long courseId);

    boolean existsByStudentIdAndCourseId(Long studentId, Long courseId);

    @Query("select e from Enrollment e where e.student.id <> :studentId and e.course.id in " +
           "(select e2.course.id from Enrollment e2 where e2.student.id = :studentId)")
    List<Enrollment> findCohortmates(@Param("studentId") Long studentId);
}
