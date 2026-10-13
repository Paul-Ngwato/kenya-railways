package com.ueab.sac;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ueab.sac.model.PlannerProject;
import com.ueab.sac.model.PlannerTask;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.PlannerProjectRepository;
import com.ueab.sac.repo.PlannerTaskRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.service.LlmClient;
import com.ueab.sac.service.planner.PlannerService;

class PlannerServiceTest {

    private User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Test
    void schedulesAroundExistingClasses() {
        User me = user(1L);

        PlannerProjectRepository projects = Mockito.mock(PlannerProjectRepository.class);
        PlannerTaskRepository tasks = Mockito.mock(PlannerTaskRepository.class);
        ScheduleEventRepository events = Mockito.mock(ScheduleEventRepository.class);
        PlannerService svc = new PlannerService(projects, tasks, events, new LlmClient());

        // save() returns the task with an id so dependency linking works
        when(tasks.save(any())).thenAnswer(inv -> {
            PlannerTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId((long) (100 + t.getOrderIndex()));
            return t;
        });

        // Monday..: a class filling 10:00-12:00 on the first day
        ScheduleEvent cls = new ScheduleEvent();
        cls.setOwner(me);
        cls.setTitle("Database class");
        cls.setDate(LocalDate.now());
        cls.setStartTime(LocalDateTime.of(LocalDate.now(), LocalTime.of(10, 0)));
        cls.setEndTime(LocalDateTime.of(LocalDate.now(), LocalTime.of(12, 0)));
        when(events.findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(anyLong(), any(), any()))
                .thenReturn(List.of(cls));

        PlannerProject p = new PlannerProject();
        p.setId(7L);
        p.setOwner(me);
        p.setName("Website Project");
        p.setDeadline(LocalDate.now().plusDays(7));
        when(projects.findById(7L)).thenReturn(Optional.of(p));

        // two 120-minute tasks, first depends on nothing, second on first
        PlannerTask t1 = new PlannerTask();
        t1.setId(101L); t1.setProject(p); t1.setTitle("Research");
        t1.setDurationMinutes(120); t1.setOrderIndex(0);
        PlannerTask t2 = new PlannerTask();
        t2.setId(102L); t2.setProject(p); t2.setTitle("Build");
        t2.setDurationMinutes(120); t2.setOrderIndex(1); t2.setDependsOn(t1);
        when(tasks.findByProjectIdOrderByOrderIndexAsc(7L)).thenReturn(List.of(t1, t2));

        PlannerService.ScheduleStats stats = svc.scheduleProject(p);

        assertEquals(2, stats.scheduled);
        assertEquals(0, stats.unscheduled);

        // both tasks got concrete slots outside the class block
        assertNotNull(t1.getPlannedDate());
        assertNotNull(t1.getPlannedStart());
        assertNotNull(t2.getPlannedDate());
        // dependency: task2 never earlier in time than task1's day
        assertFalse(t2.getPlannedDate().isBefore(t1.getPlannedDate()));

        // task1 must not overlap the 10:00-12:00 class on the same day
        if (t1.getPlannedDate().equals(LocalDate.now())) {
            boolean overlaps = t1.getPlannedStart().isBefore(LocalTime.of(12, 0))
                    && t1.getPlannedEnd().isAfter(LocalTime.of(10, 0));
            assertFalse(overlaps, "planner scheduled work inside an existing class block");
        }
    }

    @Test
    void offlinePlanProducesOrderedTasks() {
        PlannerService svc = new PlannerService(null, null, null, new LlmClient());
        var plan = svc.generatePlan("Term paper about AI in education", null,
                LocalDate.now().plusWeeks(3));
        assertTrue(plan.size() >= 5);
        assertEquals("Understand requirements", plan.get(0).title());
        assertTrue(plan.get(plan.size() - 1).minutes() > 0);
    }
}
