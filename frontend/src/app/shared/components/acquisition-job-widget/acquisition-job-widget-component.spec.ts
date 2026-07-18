import {ComponentFixture, TestBed} from '@angular/core/testing';
import {BehaviorSubject} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AcquisitionJobDto} from '../../../features/acquisition/model/acquisition.model';
import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {AcquisitionJobWidgetComponent} from './acquisition-job-widget-component';
import {AcquisitionProgressService} from '../../service/acquisition-progress.service';

describe('AcquisitionJobWidgetComponent', () => {
  let fixture: ComponentFixture<AcquisitionJobWidgetComponent>;
  let component: AcquisitionJobWidgetComponent;
  let activeJobsSubject: BehaviorSubject<Record<number, AcquisitionJobDto>>;
  let acquisitionProgressService: {
    activeJobs$: BehaviorSubject<Record<number, AcquisitionJobDto>>;
    dismissJob: ReturnType<typeof vi.fn>;
  };

  const job: AcquisitionJobDto = {
    id: 1,
    releaseTitle: 'Dune - Frank Herbert [EPUB]',
    category: 'BOOK',
    status: 'IMPORTING',
  };

  const failedJob: AcquisitionJobDto = {
    id: 2,
    releaseTitle: 'Foundation',
    category: 'AUDIOBOOK',
    status: 'FAILED',
    errorMessage: 'Download timed out',
  };

  beforeEach(async () => {
    activeJobsSubject = new BehaviorSubject<Record<number, AcquisitionJobDto>>({});
    acquisitionProgressService = {
      activeJobs$: activeJobsSubject,
      dismissJob: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [AcquisitionJobWidgetComponent, getTranslocoModule()],
      providers: [
        {provide: AcquisitionProgressService, useValue: acquisitionProgressService},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AcquisitionJobWidgetComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('tracks active jobs from the progress service', () => {
    component.ngOnInit();

    activeJobsSubject.next({[job.id]: job});

    expect(component.activeJobs).toEqual({[job.id]: job});
  });

  it('renders one row per active job with the correct status tag severity', () => {
    component.ngOnInit();
    activeJobsSubject.next({[job.id]: job, [failedJob.id]: failedJob});
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.job-card');
    expect(rows.length).toBe(2);

    expect(component.getTagSeverity('IMPORTING')).toBe('info');
    expect(component.getTagSeverity('GRABBED')).toBe('info');
    expect(component.getTagSeverity('WAITING_FOR_FILE')).toBe('info');
    expect(component.getTagSeverity('NORMALIZING')).toBe('info');
    expect(component.getTagSeverity('COMPLETED')).toBe('success');
    expect(component.getTagSeverity('FAILED')).toBe('danger');
    expect(component.getTagSeverity('TIMED_OUT')).toBe('danger');
  });

  it('shows the error message for failed jobs', () => {
    component.ngOnInit();
    activeJobsSubject.next({[failedJob.id]: failedJob});
    fixture.detectChanges();

    expect((fixture.nativeElement.textContent as string)).toContain('Download timed out');
  });

  it('does not show an error message for non-failed jobs', () => {
    component.ngOnInit();
    activeJobsSubject.next({[job.id]: job});
    fixture.detectChanges();

    expect((fixture.nativeElement.textContent as string)).not.toContain('Download timed out');
  });

  it('dismisses a job through the progress service', () => {
    component.dismissJob(job.id);

    expect(acquisitionProgressService.dismissJob).toHaveBeenCalledWith(job.id);
  });

  it('returns a category label based on the job category', () => {
    expect(component.getCategoryLabel('BOOK')).toBeTruthy();
    expect(component.getCategoryLabel('AUDIOBOOK')).toBeTruthy();
  });
});
