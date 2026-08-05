/**
 * One-off transient messages that are not network notifications — "someone deployed",
 * "theme switched", and friends. Rendered by Toasts.svelte in the same stack as the
 * notification toasts so nothing overlaps.
 */
export type FlashTone = 'info' | 'warn';

export interface Flash {
  key: number;
  text: string;
  tone: FlashTone;
}

let nextKey = 1;
let items = $state<Flash[]>([]);

export const flash = {
  get items() {
    return items;
  },
  push(text: string, tone: FlashTone = 'info', ttlMs = 7000) {
    const key = nextKey++;
    items = [...items, { key, text, tone }];
    setTimeout(() => flash.dismiss(key), ttlMs);
  },
  dismiss(key: number) {
    items = items.filter((item) => item.key !== key);
  },
};
