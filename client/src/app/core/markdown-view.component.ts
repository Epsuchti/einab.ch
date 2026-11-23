import { ChangeDetectionStrategy, Component, ElementRef, ViewEncapsulation, afterRenderEffect, computed, inject, input } from '@angular/core';
import { marked } from 'marked';

/**
 * Renders Markdown written by admins (e.g. the Bezirk info page): headings, lists, tables, links, images, code,
 * quotes. The HTML goes through Angular's sanitizer ([innerHTML]), so scripts and event handlers never reach the page.
 */
@Component({
  selector: 'app-markdown-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  template: `<div class="markdown-view" [innerHTML]="html()"></div>`,
  styles: `
    .markdown-view {
      color: var(--app-text);
      line-height: 1.6;
      overflow-wrap: anywhere;
    }
    .markdown-view > :first-child { margin-top: 0; }
    .markdown-view > :last-child { margin-bottom: 0; }
    .markdown-view :is(h1, h2, h3, h4, h5, h6) {
      margin: 1.4em 0 0.5em;
      line-height: 1.3;
      font-weight: 700;
    }
    .markdown-view h1 { font-size: 1.45rem; }
    .markdown-view h2 {
      font-size: 1.25rem;
      padding: 0.1rem 0 0.1rem 0.6rem;
      border-left: 4px solid var(--app-primary);
    }
    .markdown-view h3 { font-size: 1.1rem; }
    .markdown-view :is(h4, h5, h6) { font-size: 1rem; }
    .markdown-view :is(p, ul, ol, blockquote, pre, table) { margin: 0 0 0.9em; }
    .markdown-view :is(ul, ol) { padding-left: 1.4rem; }
    .markdown-view li + li { margin-top: 0.25em; }
    .markdown-view a {
      color: var(--app-primary-strong);
      font-weight: 600;
    }
    .markdown-view img {
      max-width: 100%;
      height: auto;
      border-radius: var(--app-radius-sm);
    }
    .markdown-view blockquote {
      padding: 0.6rem 0.9rem;
      border-left: 4px solid var(--app-info);
      border-radius: var(--app-radius-sm);
      background: var(--app-surface-muted);
      color: var(--app-text-muted);
    }
    .markdown-view blockquote > :last-child { margin-bottom: 0; }
    .markdown-view code {
      padding: 0.1em 0.35em;
      border-radius: 4px;
      background: var(--app-surface-muted);
      font-size: 0.9em;
    }
    .markdown-view pre {
      padding: 0.8rem 1rem;
      overflow-x: auto;
      border-radius: var(--app-radius-sm);
      background: var(--app-surface-muted);
    }
    .markdown-view pre code { padding: 0; background: none; }
    .markdown-view hr {
      margin: 1.5em 0;
      border: 0;
      border-top: 1px solid var(--app-border);
    }
    .markdown-view table {
      display: block;
      max-width: 100%;
      overflow-x: auto;
      border-collapse: collapse;
    }
    .markdown-view :is(th, td) {
      padding: 0.45rem 0.7rem;
      border: 1px solid var(--app-border);
      text-align: left;
      vertical-align: top;
      overflow-wrap: normal;
    }
    .markdown-view th { background: var(--app-surface-muted); }
  `
})
export class MarkdownViewComponent {
  readonly markdown = input<string | null | undefined>('');
  protected readonly html = computed(() => marked.parse(this.markdown() ?? '', { gfm: true, breaks: true, async: false }) as string);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    // Links to other sites open in a new tab, so the user does not lose the page (e.g. the onboarding step).
    afterRenderEffect(() => {
      this.html();
      this.host.nativeElement.querySelectorAll<HTMLAnchorElement>('a[href]').forEach((link) => {
        if (link.host !== window.location.host) {
          link.target = '_blank';
          link.rel = 'noopener noreferrer';
        }
      });
    });
  }
}
