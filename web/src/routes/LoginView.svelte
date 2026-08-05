<script lang="ts">
  const loginHref = '/auth/login?return=' + encodeURIComponent(location.hash || '#/live');
</script>

<div class="wrap">
  <div class="card panel">
    <h1 class="mono">CREATE DISPATCHER</h1>
    <p class="dim">Timetables and live network operations for this server.</p>
    <a class="discord" href={loginHref}>Sign in with Discord</a>
    <p class="dim small">
      No Discord app configured? An admin can mint a one-time login link with
      <code>/dispatcher web session &lt;tier&gt;</code>.
    </p>
  </div>
</div>

<style>
  .wrap {
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .card {
    width: 340px;
    padding: 28px 24px;
    text-align: center;
  }
  h1 {
    font-size: 15px;
    letter-spacing: 2px;
    margin: 0 0 8px;
  }
  .discord {
    display: block;
    margin: 18px 0;
    padding: 8px 0;
    border: 1px solid var(--station);
    border-radius: 4px;
    color: var(--station);
    text-decoration: none;
  }
  .discord:hover {
    background: var(--control);
  }
  .small {
    font-size: 11px;
  }
  code {
    font-family: var(--font-data);
    color: var(--text);
  }
</style>
