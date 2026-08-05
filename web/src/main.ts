import './theme.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { MOCK } from './lib/api/http';

mount(App, { target: document.getElementById('app')! });

if (MOCK || import.meta.env.DEV) {
  import('./lib/mock/continuity').then((m) => {
    (window as unknown as { __continuityCheck: typeof m.runContinuityCheck }).__continuityCheck =
      m.runContinuityCheck;
  });
  import('./lib/map/camera').then((m) => {
    (window as unknown as { __cam: typeof m.mapCamera }).__cam = m.mapCamera;
  });
}
