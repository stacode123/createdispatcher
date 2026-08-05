/**
 * Saved planned timetables (W5): the named assignment lists behind planner runs.
 * The list is server-side and shared — the `plans` SSE event refreshes it when another
 * planner saves. Loading one replaces the current draft in the sims store.
 */
import { MOCK, api, ApiError } from '../api/http';
import type { PlanDto, PlanSummaryDto } from '../api/types';
import { sims } from './sims.svelte';

class PlansStore {
  ui = $state({
    list: [] as PlanSummaryDto[],
    status: 'idle' as 'idle' | 'loading' | 'ready' | 'error',
    error: '',
    busy: false,
  });

  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private mockPlans: PlanDto[] = [];

  async refresh() {
    if (MOCK) {
      this.ui.list = this.mockPlans.map(summarize);
      this.ui.status = 'ready';
      return;
    }
    if (this.ui.status === 'idle') this.ui.status = 'loading';
    try {
      const dto = await api<{ plans: PlanSummaryDto[] }>('/api/plans');
      this.ui.list = dto.plans;
      this.ui.status = 'ready';
      this.ui.error = '';
    } catch (e) {
      this.ui.status = 'error';
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    }
  }

  /** SSE change signal — coalesce bursts into one refetch. */
  changed() {
    if (this.ui.status === 'idle') return; // planner not opened yet
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.refresh();
    }, 400);
  }

  /** Loads a plan into the sims draft. */
  async load(id: string) {
    this.ui.busy = true;
    this.ui.error = '';
    try {
      const plan = MOCK
        ? this.mockPlans.find((p) => p.id === id)
        : await api<PlanDto>(`/api/plans?id=${id}`);
      if (plan) sims.applyPlan(plan);
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  /** Saves the current draft. Omitting `id` creates a new plan. */
  async save(name: string, id?: string) {
    const trimmed = name.trim();
    if (!trimmed) {
      this.ui.error = 'name the plan first';
      return;
    }
    this.ui.busy = true;
    this.ui.error = '';
    try {
      const body = sims.exportDraft(trimmed, id);
      if (MOCK) {
        const saved = mockSave(body, this.mockPlans);
        this.mockPlans = saved.list;
        sims.ui.planId = saved.plan.id;
        sims.ui.planName = saved.plan.name;
      } else {
        const saved = await api<PlanDto>('/api/plans', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        sims.ui.planId = saved.id;
        sims.ui.planName = saved.name;
      }
      await this.refresh();
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  async remove(id: string) {
    this.ui.busy = true;
    this.ui.error = '';
    try {
      if (MOCK) this.mockPlans = this.mockPlans.filter((p) => p.id !== id);
      else await api(`/api/plans?id=${id}`, { method: 'DELETE' });
      if (sims.ui.planId === id) {
        sims.ui.planId = '';
        sims.ui.planName = '';
      }
      await this.refresh();
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }
}

function summarize(plan: PlanDto): PlanSummaryDto {
  return {
    id: plan.id,
    name: plan.name,
    author: plan.author,
    createdMs: plan.createdMs,
    updatedMs: plan.updatedMs,
    graphId: plan.graphId,
    removeScheduled: plan.removeScheduled,
    assignments: plan.assignments.length,
    keeps: plan.keeps.length,
    removals: plan.removals.length,
  };
}

/** Mock persistence (VITE_MOCK=1): same create-or-overwrite semantics as the server. */
function mockSave(body: Record<string, unknown>, list: PlanDto[]): { plan: PlanDto; list: PlanDto[] } {
  const now = Date.now();
  const id = (body.id as string) ?? crypto.randomUUID();
  const previous = list.find((p) => p.id === id);
  const plan = {
    ...(body as unknown as PlanDto),
    id,
    author: previous?.author ?? 'mock',
    createdMs: previous?.createdMs ?? now,
    updatedMs: now,
  };
  return { plan, list: [...list.filter((p) => p.id !== id), plan] };
}

export const plans = new PlansStore();
