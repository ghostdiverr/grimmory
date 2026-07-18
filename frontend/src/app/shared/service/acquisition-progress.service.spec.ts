import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AcquisitionJobDto} from '../../features/acquisition/model/acquisition.model';
import {AcquisitionService} from '../../features/acquisition/service/acquisition.service';
import {UserService} from '../../features/settings/user-management/user.service';
import {AcquisitionProgressService} from './acquisition-progress.service';

describe('AcquisitionProgressService', () => {
  const user = {
    permissions: {
      admin: true,
      canEditMetadata: false,
    },
  };

  let listJobs: ReturnType<typeof vi.fn>;
  let currentUser: ReturnType<typeof signal<typeof user | null>>;
  let service: AcquisitionProgressService;

  const jobA: AcquisitionJobDto = {
    id: 1,
    releaseTitle: 'Dune',
    category: 'BOOK',
    status: 'GRABBED',
  };

  const jobB: AcquisitionJobDto = {
    id: 2,
    releaseTitle: 'Foundation',
    category: 'AUDIOBOOK',
    status: 'COMPLETED',
  };

  beforeEach(() => {
    listJobs = vi.fn(() => of([]));
    currentUser = signal<typeof user | null>(user);

    TestBed.configureTestingModule({
      providers: [
        AcquisitionProgressService,
        {
          provide: AcquisitionService,
          useValue: {listJobs},
        },
        {
          provide: UserService,
          useValue: {currentUser},
        },
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('seeds active jobs once a user is present', () => {
    listJobs.mockReturnValue(of([jobA]));

    service = TestBed.inject(AcquisitionProgressService);
    TestBed.flushEffects();

    expect(listJobs).toHaveBeenCalledOnce();
    expect(service.getActiveJobs()).toEqual({[jobA.id]: jobA});
  });

  it('does not seed active jobs when there is no current user', () => {
    currentUser.set(null);

    service = TestBed.inject(AcquisitionProgressService);
    TestBed.flushEffects();

    expect(listJobs).not.toHaveBeenCalled();
    expect(service.getActiveJobs()).toEqual({});
  });

  it('warns and keeps running when the initial job fetch fails', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    listJobs.mockReturnValue(throwError(() => new Error('boom')));

    service = TestBed.inject(AcquisitionProgressService);
    TestBed.flushEffects();

    expect(listJobs).toHaveBeenCalledOnce();
    expect(warnSpy).toHaveBeenCalled();
    expect(service.getActiveJobs()).toEqual({});
  });

  it('upserts jobs on incoming updates and keeps the latest state', () => {
    service = TestBed.inject(AcquisitionProgressService);

    service.handleIncomingUpdate(jobA);
    service.handleIncomingUpdate({...jobA, status: 'IMPORTING'});

    expect(service.getActiveJobs()).toEqual({
      [jobA.id]: {...jobA, status: 'IMPORTING'},
    });
  });

  it('dismisses a job from the active job map', () => {
    service = TestBed.inject(AcquisitionProgressService);
    service.handleIncomingUpdate(jobA);
    service.handleIncomingUpdate(jobB);

    service.dismissJob(jobA.id);

    expect(service.getActiveJobs()).toEqual({[jobB.id]: jobB});
  });

  it('keeps terminal jobs visible until manually dismissed', () => {
    service = TestBed.inject(AcquisitionProgressService);

    service.handleIncomingUpdate(jobB);

    expect(service.isTerminal(jobB.status)).toBe(true);
    expect(service.getActiveJobs()).toEqual({[jobB.id]: jobB});
  });

  it('unsubscribes cleanly on destroy', () => {
    service = TestBed.inject(AcquisitionProgressService);
    service.handleIncomingUpdate(jobA);

    service.ngOnDestroy();

    expect(() => service.getActiveJobs()).not.toThrow();
  });
});
