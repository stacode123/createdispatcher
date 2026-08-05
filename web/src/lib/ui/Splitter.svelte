<script lang="ts">
  /**
   * A drag handle between two panes. Pointer capture so the drag survives leaving the
   * 8px strip, arrow keys for a keyboard path, and double-click to reset — the same
   * gestures every desktop splitter has.
   *
   * `invert` is for handles whose pane lies BEFORE them in the layout (the right column,
   * a bottom dock): there, dragging towards the pane must grow it, not shrink it.
   */
  import { BOUNDS, layout, type LayoutKey } from '../stores/layout.svelte';

  let {
    which,
    axis,
    invert = false,
    label,
  }: {
    which: LayoutKey;
    /** 'row' = a horizontal handle resizing heights; 'col' = vertical, resizing widths. */
    axis: 'row' | 'col';
    invert?: boolean;
    label: string;
  } = $props();

  let dragging = $state(false);
  let start = 0;
  let startValue = 0;

  function pointerDown(event: PointerEvent) {
    dragging = true;
    start = axis === 'row' ? event.clientY : event.clientX;
    startValue = layout.sizes[which];
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  function pointerMove(event: PointerEvent) {
    if (!dragging) return;
    const delta = (axis === 'row' ? event.clientY : event.clientX) - start;
    layout.set(which, startValue + (invert ? -delta : delta));
  }

  function pointerUp(event: PointerEvent) {
    if (!dragging) return;
    dragging = false;
    (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
  }

  /** Arrows move the HANDLE, exactly like dragging it — Shift for a bigger step. */
  function onKey(event: KeyboardEvent) {
    const back = axis === 'row' ? 'ArrowUp' : 'ArrowLeft';
    const forward = axis === 'row' ? 'ArrowDown' : 'ArrowRight';
    if (event.key !== back && event.key !== forward) return;
    event.preventDefault();
    const step = (event.shiftKey ? 48 : 12) * (event.key === forward ? 1 : -1);
    layout.set(which, layout.sizes[which] + (invert ? -step : step));
  }
</script>

<!-- The ARIA "window splitter" pattern IS a focusable separator (role=separator +
     tabindex + aria-value*); svelte's a11y rules don't model that case. -->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
  class="splitter {axis}"
  class:dragging
  role="separator"
  aria-orientation={axis === 'row' ? 'horizontal' : 'vertical'}
  aria-label={label}
  aria-valuenow={layout.sizes[which]}
  aria-valuemin={BOUNDS[which].min}
  aria-valuemax={BOUNDS[which].max}
  tabindex="0"
  onpointerdown={pointerDown}
  onpointermove={pointerMove}
  onpointerup={pointerUp}
  onpointercancel={pointerUp}
  ondblclick={() => layout.reset(which)}
  onkeydown={onKey}
  title="{label} — drag to resize, double-click to reset"
></div>

<style>
  .splitter {
    position: relative;
    background: transparent;
    touch-action: none;
    z-index: 6;
  }
  .splitter.row {
    height: 8px;
    cursor: row-resize;
  }
  .splitter.col {
    width: 8px;
    cursor: col-resize;
  }
  /* the visible hairline: thin at rest, lit while hovered or dragged */
  .splitter::after {
    content: '';
    position: absolute;
    background: var(--border);
    transition: background 0.12s;
  }
  .splitter.row::after {
    left: 0;
    right: 0;
    top: 3px;
    height: 2px;
  }
  .splitter.col::after {
    top: 0;
    bottom: 0;
    left: 3px;
    width: 2px;
  }
  .splitter:hover::after,
  .splitter:focus-visible::after,
  .splitter.dragging::after {
    background: var(--station);
  }
  .splitter:focus-visible {
    outline: none;
  }
</style>
