package com.ueab.sac.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import com.ueab.sac.model.User;
import com.ueab.sac.repo.UserRepository;

/** Helper to fetch the currently authenticated User entity. */
public final class CurrentUser {

    private CurrentUser() {}

    public static User get(UserRepository users) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String username;
        if (principal instanceof UserDetails ud) {
            username = ud.getUsername();
        } else {
            username = principal.toString();
        }
        return users.findByUsernameIgnoreCase(username)
                .or(() -> users.findByEmailIgnoreCase(username))
                .orElseThrow(() -> new IllegalStateException("No logged-in user: " + username));
    }
}
