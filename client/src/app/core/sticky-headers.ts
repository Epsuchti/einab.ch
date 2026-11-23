import { DestroyRef } from '@angular/core';

/** Height of the app bar when it stays on screen, so pinned headers can sit right below it. */
export function appStickyTop(): number {
  const header = document.querySelector<HTMLElement>('.app-header');
  if (!header) {
    return 0;
  }
  const position = getComputedStyle(header).position;
  return position === 'sticky' || position === 'fixed' ? header.getBoundingClientRect().height : 0;
}

/**
 * Keeps `--app-sticky-top` on the host in sync with the app bar height and adds `is-stuck`
 * to every element matching `selector` while it is pinned inside its parent, so it can show a shadow.
 */
export function trackStickyHeaders(host: HTMLElement, selector: string, destroyRef: DestroyRef): void {
  let frame = 0;
  const update = () => {
    frame = 0;
    const top = appStickyTop();
    host.style.setProperty('--app-sticky-top', `${top}px`);
    host.querySelectorAll<HTMLElement>(selector).forEach((element) => {
      const container = element.parentElement?.getBoundingClientRect();
      element.classList.toggle('is-stuck', !!container && container.top < top - 1 && container.bottom > top + element.offsetHeight);
    });
  };
  const schedule = () => {
    if (!frame) {
      frame = requestAnimationFrame(update);
    }
  };
  window.addEventListener('scroll', schedule, { passive: true });
  window.addEventListener('resize', schedule);
  const header = document.querySelector('.app-header');
  const resizeObserver = header ? new ResizeObserver(schedule) : null;
  if (header) {
    resizeObserver?.observe(header);
  }
  update();
  destroyRef.onDestroy(() => {
    window.removeEventListener('scroll', schedule);
    window.removeEventListener('resize', schedule);
    resizeObserver?.disconnect();
    cancelAnimationFrame(frame);
  });
}
