/**
 * Penpot Skills — dock layer.
 *
 * Penpot plugins open in a floating <plugin-modal>; the plugin API offers no
 * way to dock into the app's sidebars. This small script runs in the Penpot
 * page itself (injected into the frontend's index.html by
 * scripts/install-dock.sh) and turns the Penpot Skills plugin window into an
 * integrated, full-height side panel: snapped to the right edge, no floating
 * chrome, collapsible via an edge tab.
 *
 * Prototype note: the production-grade version of this is a native sidebar
 * panel in the frontend (ClojureScript) — this layer exists so the panel
 * integration is demoable on the prebuilt docker images.
 */
(function () {
  var PANEL_WIDTH = 420;
  var HEADER_H = 48; // Penpot workspace top bar
  var MATCH = 'localhost:4500'; // the Penpot Skills plugin UI origin
  var docked = new WeakSet();

  function findPanel() {
    var modals = document.querySelectorAll('plugin-modal');
    for (var i = 0; i < modals.length; i++) {
      var src = modals[i].getAttribute('iframe-src') || '';
      if (src.indexOf(MATCH) !== -1) return modals[i];
    }
    return null;
  }

  function dock(modal) {
    if (docked.has(modal)) return;
    docked.add(modal);

    // Host: fixed panel on the right, under the top bar.
    modal.style.position = 'fixed';
    modal.style.left = 'auto';
    modal.style.right = '0';
    modal.style.top = HEADER_H + 'px';
    modal.style.bottom = '0';
    modal.style.width = PANEL_WIDTH + 'px';
    modal.style.height = 'calc(100vh - ' + HEADER_H + 'px)';
    modal.style.zIndex = '12';

    // Wrapper: neutralize floating-window geometry (inset vars from the drag
    // handler, resize handle, size caps, floating chrome).
    var style = document.createElement('style');
    style.textContent =
      '.wrapper{transform:none !important;inset:0 !important;' +
      'width:100% !important;height:100% !important;' +
      'max-inline-size:none !important;max-block-size:none !important;' +
      'min-inline-size:0 !important;padding:4px !important;resize:none !important;' +
      'border-radius:0 !important;box-shadow:none !important;' +
      'border:0 !important;border-inline-start:1px solid rgba(128,128,128,.35) !important;}' +
      '.wrapper:after{display:none !important;}' +
      '.header{cursor:default !important;}';
    if (modal.shadowRoot) modal.shadowRoot.appendChild(style);

    ensureToggle();
  }

  function ensureToggle() {
    if (document.getElementById('penpot-skills-dock-toggle')) return;
    var btn = document.createElement('button');
    btn.id = 'penpot-skills-dock-toggle';
    btn.textContent = '⛨';
    btn.title = 'Toggle Penpot Skills panel';
    btn.style.cssText =
      'position:fixed;right:0;top:50%;transform:translateY(-50%);z-index:13;' +
      'width:24px;height:64px;border:1px solid rgba(128,128,128,.4);border-right:none;' +
      'border-radius:8px 0 0 8px;background:#18181a;color:#e5e5e6;cursor:pointer;' +
      'font-size:13px;padding:0;';
    btn.addEventListener('click', function () {
      var panel = findPanel();
      if (!panel) return;
      panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
    });
    document.body.appendChild(btn);
  }

  function sweep() {
    var panel = findPanel();
    if (panel) dock(panel);
    var btn = document.getElementById('penpot-skills-dock-toggle');
    if (btn && !panel) btn.remove(); // plugin closed → tab disappears too
  }

  new MutationObserver(sweep).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
  sweep();
})();
