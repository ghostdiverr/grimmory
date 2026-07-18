import {computed, effect, inject, Injectable, OnDestroy} from '@angular/core';
import {BehaviorSubject, Subscription} from 'rxjs';
import {toSignal} from '@angular/core/rxjs-interop';
import {AcquisitionJobDto} from '../../features/acquisition/model/acquisition.model';
import {AcquisitionService} from '../../features/acquisition/service/acquisition.service';
import {UserService} from '../../features/settings/user-management/user.service';

const TERMINAL_STATUSES: ReadonlySet<AcquisitionJobDto['status']> = new Set([
  'COMPLETED',
  'FAILED',
  'TIMED_OUT',
]);

@Injectable({providedIn: 'root'})
export class AcquisitionProgressService implements OnDestroy {
  private jobMap = new Map<number, BehaviorSubject<AcquisitionJobDto>>();

  private activeJobsSubject = new BehaviorSubject<Record<number, AcquisitionJobDto>>({});
  activeJobs$ = this.activeJobsSubject.asObservable();

  private readonly activeJobsSignal = toSignal(this.activeJobs$, {initialValue: {}});
  readonly hasActiveJobs = computed(() => Object.keys(this.activeJobsSignal()).length > 0);

  private acquisitionService = inject(AcquisitionService);
  private userService = inject(UserService);

  private subscriptions = new Subscription();
  private hasInitialized = false;

  constructor() {
    effect(() => {
      const user = this.userService.currentUser();
      if (this.hasInitialized || !user) {
        return;
      }
      this.hasInitialized = true;

      const listJobsSub = this.acquisitionService.listJobs().subscribe({
        next: (jobs) => this.initializeActiveJobs(jobs),
        error: (err) => console.warn('Failed to fetch active acquisition jobs:', err)
      });
      this.subscriptions.add(listJobsSub);
    });
  }

  handleIncomingUpdate(job: AcquisitionJobDto): void {
    if (!this.jobMap.has(job.id)) {
      this.jobMap.set(job.id, new BehaviorSubject(job));
    } else {
      this.jobMap.get(job.id)!.next(job);
    }

    this.activeJobsSubject.next(this.getActiveJobs());
  }

  dismissJob(id: number): void {
    this.jobMap.delete(id);
    this.activeJobsSubject.next(this.getActiveJobs());
  }

  getActiveJobs(): Record<number, AcquisitionJobDto> {
    const result: Record<number, AcquisitionJobDto> = {};
    this.jobMap.forEach((subject, id) => {
      result[id] = subject.getValue();
    });
    return result;
  }

  isTerminal(status: AcquisitionJobDto['status']): boolean {
    return TERMINAL_STATUSES.has(status);
  }

  private initializeActiveJobs(jobs: AcquisitionJobDto[]): void {
    for (const job of jobs) {
      this.jobMap.set(job.id, new BehaviorSubject(job));
    }
    this.activeJobsSubject.next(this.getActiveJobs());
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.jobMap.forEach(subject => subject.complete());
    this.activeJobsSubject.complete();
  }
}
