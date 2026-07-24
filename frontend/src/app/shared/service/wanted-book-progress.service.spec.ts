import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {WantedBookDto} from '../../features/wanted/model/wanted.model';
import {WantedBookService} from '../../features/wanted/service/wanted-book.service';
import {UserService} from '../../features/settings/user-management/user.service';
import {WantedBookProgressService} from './wanted-book-progress.service';

describe('WantedBookProgressService', () => {
  const user = {
    permissions: {
      admin: true,
      canEditMetadata: false,
    },
  };

  let list: ReturnType<typeof vi.fn>;
  let currentUser: ReturnType<typeof signal<typeof user | null>>;
  let service: WantedBookProgressService;

  const entryA: WantedBookDto = {
    id: 1,
    title: 'Dune',
    category: 'BOOK',
    libraryId: 3,
    status: 'ACTIVE',
    requestedByUserId: 7,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  const entryB: WantedBookDto = {
    id: 2,
    title: 'Foundation',
    category: 'AUDIOBOOK',
    libraryId: 4,
    status: 'FULFILLED',
    requestedByUserId: 7,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    list = vi.fn(() => of([]));
    currentUser = signal<typeof user | null>(user);

    TestBed.configureTestingModule({
      providers: [
        WantedBookProgressService,
        {
          provide: WantedBookService,
          useValue: {list},
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

  it('seeds active entries once a user is present', () => {
    list.mockReturnValue(of([entryA]));

    service = TestBed.inject(WantedBookProgressService);
    TestBed.flushEffects();

    expect(list).toHaveBeenCalledOnce();
    expect(service.getActiveEntries()).toEqual({[entryA.id]: entryA});
  });

  it('does not seed active entries when there is no current user', () => {
    currentUser.set(null);

    service = TestBed.inject(WantedBookProgressService);
    TestBed.flushEffects();

    expect(list).not.toHaveBeenCalled();
    expect(service.getActiveEntries()).toEqual({});
  });

  it('warns and keeps running when the initial fetch fails', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    list.mockReturnValue(throwError(() => new Error('boom')));

    service = TestBed.inject(WantedBookProgressService);
    TestBed.flushEffects();

    expect(list).toHaveBeenCalledOnce();
    expect(warnSpy).toHaveBeenCalled();
    expect(service.getActiveEntries()).toEqual({});
  });

  it('upserts entries on incoming updates and keeps the latest state', () => {
    service = TestBed.inject(WantedBookProgressService);

    service.handleIncomingUpdate(entryA);
    service.handleIncomingUpdate({...entryA, status: 'GRABBED'});

    expect(service.getActiveEntries()).toEqual({
      [entryA.id]: {...entryA, status: 'GRABBED'},
    });
  });

  it('exposes entries as a signal derived from the active entry map', () => {
    service = TestBed.inject(WantedBookProgressService);

    service.handleIncomingUpdate(entryA);
    service.handleIncomingUpdate(entryB);

    expect(service.entries()).toEqual(expect.arrayContaining([entryA, entryB]));
    expect(service.entries()).toHaveLength(2);
  });

  it('removes an entry from the active entry map', () => {
    service = TestBed.inject(WantedBookProgressService);
    service.handleIncomingUpdate(entryA);
    service.handleIncomingUpdate(entryB);

    service.removeEntry(entryA.id);

    expect(service.getActiveEntries()).toEqual({[entryB.id]: entryB});
  });

  it('unsubscribes cleanly on destroy', () => {
    service = TestBed.inject(WantedBookProgressService);
    service.handleIncomingUpdate(entryA);

    service.ngOnDestroy();

    expect(() => service.getActiveEntries()).not.toThrow();
  });
});
