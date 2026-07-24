import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ConfirmationService, MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';

import {WantedBookProgressService} from '../../../../shared/service/wanted-book-progress.service';
import {WantedBookService} from '../../service/wanted-book.service';
import {DialogLauncherService} from '../../../../shared/services/dialog-launcher.service';
import {LibraryService} from '../../../book/service/library.service';
import {WantedBookDto} from '../../model/wanted.model';
import {WantedListComponent} from './wanted-list.component';

describe('WantedListComponent', () => {
  const pause = vi.fn();
  const resume = vi.fn();
  const del = vi.fn();
  const openWantedBookCreateDialog = vi.fn();
  const messageAdd = vi.fn();
  const translate = vi.fn((key: string) => key);
  const confirm = vi.fn();
  const handleIncomingUpdate = vi.fn();
  const removeEntry = vi.fn();

  const entries = signal<WantedBookDto[]>([]);
  const libraries = signal([
    {id: 1, name: 'Fiction'},
    {id: 2, name: 'Non-Fiction'},
  ]);

  const entryActive: WantedBookDto = {
    id: 1,
    title: 'Dune',
    category: 'BOOK',
    libraryId: 1,
    status: 'ACTIVE',
    requestedByUserId: 7,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  const entryPaused: WantedBookDto = {
    ...entryActive,
    id: 2,
    title: 'Foundation',
    status: 'PAUSED',
    createdAt: '2026-01-02T00:00:00Z',
  };

  beforeEach(() => {
    pause.mockReset();
    resume.mockReset();
    del.mockReset();
    openWantedBookCreateDialog.mockReset().mockReturnValue(Promise.resolve(null));
    messageAdd.mockClear();
    translate.mockClear();
    confirm.mockReset();
    handleIncomingUpdate.mockClear();
    removeEntry.mockClear();
    entries.set([]);

    TestBed.configureTestingModule({
      providers: [
        {provide: WantedBookProgressService, useValue: {entries, handleIncomingUpdate, removeEntry}},
        {provide: WantedBookService, useValue: {pause, resume, delete: del}},
        {provide: DialogLauncherService, useValue: {openWantedBookCreateDialog}},
        {provide: LibraryService, useValue: {libraries}},
        {provide: ConfirmationService, useValue: {confirm}},
        {provide: MessageService, useValue: {add: messageAdd}},
        {provide: TranslocoService, useValue: {translate}},
      ],
    });
  });

  function createComponent(): WantedListComponent {
    return TestBed.runInInjectionContext(() => new WantedListComponent());
  }

  it('sorts entries by newest created first', () => {
    entries.set([entryActive, entryPaused]);
    const component = createComponent();

    expect(component.entries().map(e => e.id)).toEqual([2, 1]);
  });

  it('resolves the library name for an entry', () => {
    const component = createComponent();

    expect(component.libraryName(entryActive)).toBe('Fiction');
    expect(component.libraryName({...entryActive, libraryId: 999})).toBe('—');
  });

  it('allows toggling pause only for active or paused entries', () => {
    const component = createComponent();

    expect(component.canTogglePause(entryActive)).toBe(true);
    expect(component.canTogglePause(entryPaused)).toBe(true);
    expect(component.canTogglePause({...entryActive, status: 'GRABBED'})).toBe(false);
    expect(component.canTogglePause({...entryActive, status: 'FULFILLED'})).toBe(false);
  });

  it('opens the create dialog via the dialog launcher service', () => {
    const component = createComponent();

    component.openCreateDialog();

    expect(openWantedBookCreateDialog).toHaveBeenCalledOnce();
  });

  it('pauses an active entry and merges the update on success', () => {
    pause.mockReturnValue(of({...entryActive, status: 'PAUSED'}));
    const component = createComponent();

    component.togglePause(entryActive);

    expect(pause).toHaveBeenCalledWith(entryActive.id);
    expect(handleIncomingUpdate).toHaveBeenCalledWith({...entryActive, status: 'PAUSED'});
    expect(component.isPending(entryActive)).toBe(false);
  });

  it('resumes a paused entry', () => {
    resume.mockReturnValue(of({...entryPaused, status: 'ACTIVE'}));
    const component = createComponent();

    component.togglePause(entryPaused);

    expect(resume).toHaveBeenCalledWith(entryPaused.id);
    expect(handleIncomingUpdate).toHaveBeenCalledWith({...entryPaused, status: 'ACTIVE'});
  });

  it('shows an error toast when pausing fails and clears the pending state', () => {
    pause.mockReturnValue(throwError(() => new Error('boom')));
    const component = createComponent();

    component.togglePause(entryActive);

    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'error'}));
    expect(component.isPending(entryActive)).toBe(false);
  });

  it('ignores a toggle request while an action is already pending for that entry', () => {
    pause.mockReturnValue(of({...entryActive, status: 'PAUSED'}));
    const component = createComponent();

    component.pendingActionId.set(entryActive.id);
    component.togglePause(entryActive);

    expect(pause).not.toHaveBeenCalled();
  });

  it('confirms before deleting, then removes the entry and shows a success toast', () => {
    del.mockReturnValue(of(undefined));
    confirm.mockImplementation((config: {accept?: () => void}) => config.accept?.());
    const component = createComponent();

    component.confirmDelete(entryActive);

    expect(confirm).toHaveBeenCalledOnce();
    expect(del).toHaveBeenCalledWith(entryActive.id);
    expect(removeEntry).toHaveBeenCalledWith(entryActive.id);
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'success'}));
  });

  it('does not delete when the confirmation is not accepted', () => {
    confirm.mockImplementation(() => undefined);
    const component = createComponent();

    component.confirmDelete(entryActive);

    expect(del).not.toHaveBeenCalled();
    expect(removeEntry).not.toHaveBeenCalled();
  });

  it('shows an error toast when delete fails', () => {
    del.mockReturnValue(throwError(() => new Error('boom')));
    confirm.mockImplementation((config: {accept?: () => void}) => config.accept?.());
    const component = createComponent();

    component.confirmDelete(entryActive);

    expect(removeEntry).not.toHaveBeenCalled();
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'error'}));
  });

  it('maps status to a tag color', () => {
    const component = createComponent();

    expect(component.statusColor('ACTIVE')).toBe('blue');
    expect(component.statusColor('GRABBED')).toBe('amber');
    expect(component.statusColor('FULFILLED')).toBe('green');
    expect(component.statusColor('PAUSED')).toBe('gray');
  });
});
