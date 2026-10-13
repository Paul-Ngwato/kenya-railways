// Shared UI helpers for Smart Academic Companion

// Highlight the sidebar entry matching the current path
(function () {
  var path = window.location.pathname;
  document.querySelectorAll('.sidebar .sb-item').forEach(function (a) {
    var href = a.getAttribute('href');
    if (href && href.indexOf('#') === -1 && (path === href || path.indexOf(href + '/') === 0)) {
      a.classList.add('active');
    }
  });
})();

// Deep-link: open the quick-add modal when arriving with #addActivityModal
(function () {
  if (window.location.hash === '#addActivityModal') {
    var m = document.getElementById('addActivityModal');
    if (m) m.hidden = false;
  }
})();

// Generic modal open/close (pages with modals declare data-open-modal / data-close-modal)
document.querySelectorAll('[data-open-modal]').forEach(function (btn) {
  btn.addEventListener('click', function () {
    var m = document.querySelector(btn.getAttribute('data-open-modal'));
    if (m) m.hidden = false;
  });
});
document.querySelectorAll('[data-close-modal]').forEach(function (btn) {
  btn.addEventListener('click', function () {
    btn.closest('.modal-backdrop').hidden = true;
  });
});
document.querySelectorAll('.modal-backdrop').forEach(function (bd) {
  bd.addEventListener('click', function (e) { if (e.target === bd) bd.hidden = true; });
});

// Quick-add sidebar link opens the modal on the dashboard instead of navigating
(function () {
  var m = document.getElementById('addActivityModal');
  if (!m) return;
  document.querySelectorAll('a[href$="#addActivityModal"]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      e.preventDefault();
      m.hidden = false;
    });
  });
})();
