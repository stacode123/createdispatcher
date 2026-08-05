// Hand-rolled hash router: routes never reach the JDK server, deep links survive reloads.
export type Route = 'live' | 'planner' | 'replay';

let route = $state<Route>('live');
let params = $state<Record<string, string>>({});

function parse() {
  const hash = location.hash || '#/live';
  const match = hash.match(/^#\/([a-z]*)(?:\?(.*))?$/);
  const name: Route = match?.[1] === 'planner' ? 'planner' : match?.[1] === 'replay' ? 'replay' : 'live';
  const query: Record<string, string> = {};
  if (match?.[2]) {
    for (const pair of match[2].split('&')) {
      const eq = pair.indexOf('=');
      if (eq > 0) query[decodeURIComponent(pair.slice(0, eq))] = decodeURIComponent(pair.slice(eq + 1));
    }
  }
  route = name;
  params = query;
}

window.addEventListener('hashchange', parse);
parse();

export const router = {
  get route() {
    return route;
  },
  get params() {
    return params;
  },
  navigate(to: Route, query?: Record<string, string>) {
    const search = query && Object.keys(query).length
      ? '?' + Object.entries(query).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
      : '';
    location.hash = `#/${to}${search}`;
  },
};
