package com.ueab.sac.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ueab.sac.model.Course;
import com.ueab.sac.model.Enrollment;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.UserRepository;
import com.ueab.sac.security.CurrentUser;

/** Course Cohort Network module. */
@Controller
@RequestMapping("/courses")
public class CoursesController {

    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;

    public CoursesController(UserRepository users, CourseRepository courses,
                             EnrollmentRepository enrollments) {
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
    }

    @GetMapping
    public String list(Model model) {
        User me = CurrentUser.get(users);
        List<Enrollment> mine = enrollments.findByStudentId(me.getId());

        // cohort mates grouped per course
        Map<Long, List<User>> matesByCourse = new LinkedHashMap<>();
        for (Enrollment e : mine) {
            List<User> mates = new ArrayList<>();
            for (Enrollment other : enrollments.findByCourseId(e.getCourse().getId())) {
                if (!other.getStudent().getId().equals(me.getId())) {
                    mates.add(other.getStudent());
                }
            }
            matesByCourse.put(e.getCourse().getId(), mates);
        }

        model.addAttribute("me", me);
        model.addAttribute("enrollments", mine);
        model.addAttribute("matesByCourse", matesByCourse);
        model.addAttribute("allCourses", courses.findAll());
        return "courses";
    }

    @PostMapping("/join")
    public String join(@RequestParam Long courseId) {
        User me = CurrentUser.get(users);
        Course c = courses.findById(courseId).orElse(null);
        if (c != null && !enrollments.existsByStudentIdAndCourseId(me.getId(), courseId)) {
            Enrollment e = new Enrollment();
            e.setStudent(me);
            e.setCourse(c);
            enrollments.save(e);
        }
        return "redirect:/courses";
    }

    @PostMapping("/{courseId}/leave")
    public String leave(@PathVariable Long courseId) {
        User me = CurrentUser.get(users);
        enrollments.findByStudentId(me.getId()).stream()
                .filter(e -> e.getCourse().getId().equals(courseId))
                .forEach(enrollments::delete);
        return "redirect:/courses";
    }
}
