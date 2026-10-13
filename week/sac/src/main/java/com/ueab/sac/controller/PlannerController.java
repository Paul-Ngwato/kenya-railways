package com.ueab.sac.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ueab.sac.model.PlannerProject;
import com.ueab.sac.model.PlannerTask;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.PlannerProjectRepository;
import com.ueab.sac.repo.PlannerTaskRepository;
import com.ueab.sac.repo.UserRepository;
import com.ueab.sac.security.CurrentUser;
import com.ueab.sac.service.planner.PlannerService;

/**
 * Planner: goals in, dependency-aware work sessions out — fitted around
 * the student's real calendar. Suggested sessions stay "PLANNED" until the
 * user accepts them (user remains in control).
 */
@Controller
@RequestMapping("/planner")
public class PlannerController {

    private final UserRepository users;
    private final PlannerProjectRepository projects;
    private final PlannerTaskRepository tasks;
    private final PlannerService planner;

    public PlannerController(UserRepository users,
                             PlannerProjectRepository projects,
                             PlannerTaskRepository tasks,
                             PlannerService planner) {
        this.users = users;
        this.projects = projects;
        this.tasks = tasks;
        this.planner = planner;
    }

    @GetMapping
    public String list(Model model) {
        User me = CurrentUser.get(users);
        List<PlannerProject> my = projects.findWithTasksByOwnerId(me.getId());
        model.addAttribute("me", me);
        model.addAttribute("projects", my);
        model.addAttribute("upcomingSessions", tasks.findByProjectOwnerIdAndStatus(
                me.getId(), PlannerTask.Status.SCHEDULED).stream()
                .filter(t -> t.getPlannedDate() != null && !t.getPlannedDate().isBefore(LocalDate.now()))
                .limit(8)
                .toList());
        return "planner";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        User me = CurrentUser.get(users);
        PlannerProject p = projects.findWithTasksById(id)
                .filter(x -> x.getOwner().getId().equals(me.getId()))
                .orElse(null);
        if (p == null) return "redirect:/planner";

        model.addAttribute("me", me);
        model.addAttribute("project", p);
        model.addAttribute("tasks", tasks.findByProjectIdOrderByOrderIndexAsc(p.getId()));
        return "planner-project";
    }

    /** Creates a project, generates the plan and (optionally) schedules it. */
    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String details,
                         @RequestParam(required = false) LocalDate deadline,
                         @RequestParam(defaultValue = "true") boolean autoSchedule) {
        User me = CurrentUser.get(users);
        PlannerProject p = planner.createProject(me, name, details, deadline, autoSchedule);
        return "redirect:/planner/" + p.getId();
    }

    /** Re-runs the calendar-aware scheduler for one project. */
    @PostMapping("/{id}/schedule")
    public String schedule(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        projects.findById(id)
                .filter(p -> p.getOwner().getId().equals(me.getId()))
                .ifPresent(planner::scheduleProject);
        return "redirect:/planner/" + id;
    }

    /** Accepts the proposed schedule: creates the calendar blocks. */
    @PostMapping("/{id}/accept")
    public String accept(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        projects.findById(id)
                .filter(p -> p.getOwner().getId().equals(me.getId()))
                .ifPresent(planner::acceptPlan);
        return "redirect:/planner/" + id;
    }

    @PostMapping("/{id}/complete")
    public String completeProject(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        projects.findById(id)
                .filter(p -> p.getOwner().getId().equals(me.getId()))
                .ifPresent(p -> p.setStatus(PlannerProject.Status.COMPLETED));
        return "redirect:/planner";
    }

    @PostMapping("/{id}/delete")
    public String deleteProject(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        projects.findById(id)
                .filter(p -> p.getOwner().getId().equals(me.getId()))
                .ifPresent(planner::deleteProject);
        return "redirect:/planner";
    }

    @PostMapping("/tasks/{taskId}/complete")
    public String completeTask(@PathVariable Long taskId,
                               @RequestParam(required = false) Long project) {
        User me = CurrentUser.get(users);
        tasks.findById(taskId)
                .filter(t -> t.getProject().getOwner().getId().equals(me.getId()))
                .ifPresent(planner::completeTask);
        return project != null ? "redirect:/planner/" + project : "redirect:/planner";
    }

    @PostMapping("/tasks/{taskId}/skip")
    public String skipTask(@PathVariable Long taskId,
                           @RequestParam(required = false) Long project) {
        User me = CurrentUser.get(users);
        tasks.findById(taskId)
                .filter(t -> t.getProject().getOwner().getId().equals(me.getId()))
                .ifPresent(planner::skipTask);
        return project != null ? "redirect:/planner/" + project : "redirect:/planner";
    }

    @PostMapping("/tasks/{taskId}/reopen")
    public String reopenTask(@PathVariable Long taskId,
                             @RequestParam(required = false) Long project) {
        User me = CurrentUser.get(users);
        tasks.findById(taskId)
                .filter(t -> t.getProject().getOwner().getId().equals(me.getId()))
                .ifPresent(planner::reopenTask);
        return project != null ? "redirect:/planner/" + project : "redirect:/planner";
    }
}
