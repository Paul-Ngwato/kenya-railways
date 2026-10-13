package com.ueab.sac.controller;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.ueab.sac.model.InboxItem;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.InboxItemRepository;
import com.ueab.sac.repo.UserRepository;
import com.ueab.sac.security.CurrentUser;
import com.ueab.sac.service.inbox.InboxService;

/**
 * AI Inbox: upload / paste anything, AI extracts structured events,
 * the user verifies each one before it reaches the calendar.
 */
@Controller
@RequestMapping("/inbox")
public class InboxController {

    private final UserRepository users;
    private final InboxItemRepository items;
    private final InboxService inbox;

    public InboxController(UserRepository users, InboxItemRepository items, InboxService inbox) {
        this.users = users;
        this.items = items;
        this.inbox = inbox;
    }

    /** Inbox page: capture form + verification stepper + history. */
    @GetMapping
    public String page(@RequestParam(required = false) Long verify,
                       @RequestParam(required = false) String flash,
                       Model model) {
        User me = CurrentUser.get(users);
        List<InboxItem> pending = items.findByOwnerIdAndStatusOrderByCreatedAtAsc(
                me.getId(), InboxItem.Status.PENDING);

        InboxItem current = null;
        if (verify != null) {
            current = items.findById(verify)
                    .filter(i -> i.getOwner().getId().equals(me.getId()))
                    .filter(i -> i.getStatus() == InboxItem.Status.PENDING)
                    .orElse(null);
        } else if (!pending.isEmpty()) {
            current = pending.get(0);
        }

        model.addAttribute("me", me);
        model.addAttribute("pending", pending);
        model.addAttribute("current", current);
        // weekly rows: pre-fill the date with the class's next occurrence
        model.addAttribute("suggestedDate",
                current != null && current.getWeeklyDays() != null
                        ? InboxService.nextOccurrence(current.getWeeklyDays(), LocalDate.now())
                        : null);
        // flyers with a stated date range pre-select the detected week count
        model.addAttribute("suggestedWeeks",
                current != null ? InboxService.suggestedWeeks(current.getWarning()) : 0);
        model.addAttribute("currentTypeOptions", typeOptions());
        model.addAttribute("history", items.findByOwnerIdOrderByCreatedAtDesc(me.getId()).stream()
                .filter(i -> i.getStatus() != InboxItem.Status.PENDING)
                .limit(15)
                .toList());
        if (flash != null) model.addAttribute("flashMsg", flash);
        return "inbox";
    }

    /** Paste-anything capture. */
    @PostMapping("/paste")
    public String paste(@RequestParam String text) {
        User me = CurrentUser.get(users);
        if (text != null && !text.isBlank()) {
            inbox.ingestText(me, text.trim());
        }
        return "redirect:/inbox";
    }

    /** Multi-file capture. */
    @PostMapping("/upload")
    public String upload(@RequestParam("files") List<MultipartFile> files) {
        User me = CurrentUser.get(users);
        if (files != null && !files.isEmpty()) {
            inbox.ingestFiles(me, files);
        }
        return "redirect:/inbox";
    }

    /** Confirm current item (with optional edits) -> creates the calendar event(s). */
    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable Long id,
                          @RequestParam String title,
                          @RequestParam String type,
                          @RequestParam LocalDate date,
                          @RequestParam(required = false) LocalTime start,
                          @RequestParam(required = false) LocalTime end,
                          @RequestParam(required = false) String venue,
                          @RequestParam(required = false) String notes,
                          @RequestParam(defaultValue = "1") int weeks) {
        User me = CurrentUser.get(users);
        int safeWeeks = Math.max(1, Math.min(weeks, 20));
        items.findById(id)
                .filter(i -> i.getOwner().getId().equals(me.getId()))
                .filter(i -> i.getStatus() == InboxItem.Status.PENDING)
                .ifPresent(i -> inbox.confirm(me, i, title, type, date, start, end, venue, notes, safeWeeks));
        return nextAfter(id, me);
    }

    /** Reject without adding anything — the item is removed entirely. */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        items.findById(id)
                .filter(i -> i.getOwner().getId().equals(me.getId()))
                .filter(i -> i.getStatus() == InboxItem.Status.PENDING)
                .ifPresent(inbox::reject);
        return nextAfter(id, me);
    }

    /** Permanently removes a processed item from the history list. */
    @PostMapping("/history/{id}/delete")
    public String deleteHistory(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        inbox.deleteProcessed(me, id);
        return "redirect:/inbox";
    }

    /** Duplicate resolution: keep the existing calendar event, drop the item. */
    @PostMapping("/{id}/keep-existing")
    public String keepExisting(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        items.findById(id)
                .filter(i -> i.getOwner().getId().equals(me.getId()))
                .filter(i -> i.getStatus() == InboxItem.Status.PENDING)
                .ifPresent(inbox::keepExisting);
        return nextAfter(id, me);
    }

    /** Jump to a specific pending item from the progress dots. */
    @PostMapping("/{id}/skip")
    public String skip(@PathVariable Long id) {
        return "redirect:/inbox?verify=" + id;
    }

    /** Redirects to the next pending item (or the inbox). */
    private String nextAfter(Long id, User me) {
        List<InboxItem> pending = items.findByOwnerIdAndStatusOrderByCreatedAtAsc(
                me.getId(), InboxItem.Status.PENDING);
        return pending.isEmpty()
                ? "redirect:/inbox"
                : "redirect:/inbox?verify=" + pending.get(0).getId();
    }

    private static List<String> typeOptions() {
        return List.of("CLASS", "ASSIGNMENT", "STUDY", "PRACTICAL", "CAT", "EXAM", "DEADLINE",
                "CLUB", "SPORT", "WORSHIP", "VOLUNTEER",
                "MEETING", "EVENT", "WORK", "REMINDER", "OTHER");
    }
}
