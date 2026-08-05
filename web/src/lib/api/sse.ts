// The single multiplexed event stream. One EventSource per app (browsers cap HTTP/1.1
// connections at 6 per origin); native auto-reconnect (server sends retry:) resends
// Last-Event-ID; a staleness watchdog catches half-dead proxy streams (the server broadcasts
// trains every ~1s, so >25s of silence means the stream is gone).
import { MOCK, api } from './http';
import type { DeployedEventDto, HelloEvent, SimEventDto, TrainsEvent, WebNotification } from './types';

export interface SseHandlers {
  hello?: (data: HelloEvent, receivedAt: number) => void;
  trains?: (data: TrainsEvent, receivedAt: number) => void;
  trainMeta?: (data: { rosterVersion: number }) => void;
  graph?: (data: { id: string; version: number }) => void;
  graphIndex?: (data: unknown) => void;
  notify?: (data: WebNotification) => void;
  replay?: (data: { id: string; notificationId: string; kind: string }) => void;
  presets?: (data: unknown) => void;
  plans?: (data: unknown) => void;
  trainFolders?: (data: unknown) => void;
  sim?: (data: SimEventDto) => void;
  deployed?: (data: DeployedEventDto) => void;
  reset?: () => void;
}

let source: EventSource | null = null;
let handlers: SseHandlers = {};
let lastEventAt = 0;
let watchdog: ReturnType<typeof setInterval> | null = null;
let reopenTimer: ReturnType<typeof setTimeout> | null = null;
let backoffMs = 1000;

export function connectSse(h: SseHandlers) {
  if (MOCK) return;
  handlers = h;
  open();
  if (!watchdog) {
    watchdog = setInterval(() => {
      if (source && lastEventAt && Date.now() - lastEventAt > 25_000) reopen();
    }, 10_000);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && source && lastEventAt && Date.now() - lastEventAt > 5_000)
        reopen();
    });
  }
}

export function disconnectSse() {
  if (watchdog) clearInterval(watchdog);
  watchdog = null;
  source?.close();
  source = null;
}

function open() {
  source?.close();
  source = new EventSource('/api/events');
  const on = <T>(name: string, handler?: (data: T, receivedAt: number) => void) => {
    source!.addEventListener(name, (event) => {
      lastEventAt = Date.now();
      backoffMs = 1000;
      if (handler) handler(JSON.parse((event as MessageEvent).data) as T, lastEventAt);
    });
  };
  on('hello', handlers.hello);
  on('trains', handlers.trains);
  on('trainMeta', handlers.trainMeta);
  on('graph', handlers.graph);
  on('graphIndex', handlers.graphIndex);
  on('notify', handlers.notify);
  on('replay', handlers.replay);
  on('presets', handlers.presets);
  on('plans', handlers.plans);
  on('trainFolders', handlers.trainFolders);
  on('sim', handlers.sim);
  on('deployed', handlers.deployed);
  on('reset', handlers.reset ? () => handlers.reset!() : undefined);
  source.onerror = () => {
    // EventSource can't expose status codes; a CLOSED stream after an auth expiry needs a probe.
    if (source?.readyState === EventSource.CLOSED) {
      api('/api/me').catch(() => {}); // 401 flips the session store via the global handler
      if (reopenTimer) clearTimeout(reopenTimer);
      reopenTimer = setTimeout(() => reopen(), (backoffMs = Math.min(30_000, backoffMs * 2)));
    }
  };
}

function reopen() {
  if (!source) return;
  open();
}
