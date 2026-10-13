package com.ueab.sac.controller;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ueab.sac.model.Enrollment;
import com.ueab.sac.model.Role;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.UserRepository;

/** Registration and automatic course enrollment. */
@Controller
public class AuthController {

    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository users, CourseRepository courses,
                          EnrollmentRepository enrollments, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    @GetMapping("/register")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username,
                           @RequestParam String fullName,
                           @RequestParam String email,
                           @RequestParam String password,
                           @RequestParam(name = "courseCodes", required = false) String courseCodes) {
        if (username == null || username.isBlank()
                || users.findByUsernameIgnoreCase(username).isPresent()
                || users.existsByEmailIgnoreCase(email)) {
            return "redirect:/register?error=exists";
        }

        User u = users.save(new User(username.trim(), fullName.trim(), email.trim(),
                passwordEncoder.encode(password), Role.STUDENT));

        // Auto-enroll in comma-separated course codes (Course Cohort Network seed)
        if (courseCodes != null && !courseCodes.isBlank()) {
            for (String code : courseCodes.split(",")) {
                String c = code.trim();
                if (c.isEmpty()) continue;
                courses.findByCodeIgnoreCase(c).ifPresent(course -> {
                    if (!enrollments.existsByStudentIdAndCourseId(u.getId(), course.getId())) {
                        Enrollment e = new Enrollment();
                        e.setStudent(u);
                        e.setCourse(course);
                        enrollments.save(e);
                    }
                });
            }
        }
        return "redirect:/login?registered";
    }
}
