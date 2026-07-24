import {ChangeDetectionStrategy, Component, computed, inject, signal} from '@angular/core';
import {TableModule} from 'primeng/table';
import {Button} from 'primeng/button';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {ConfirmationService, MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {DatePipe} from '@angular/common';
import {WantedBookProgressService} from '../../../../shared/service/wanted-book-progress.service';
import {WantedBookService} from '../../service/wanted-book.service';
import {DialogLauncherService} from '../../../../shared/services/dialog-launcher.service';
import {LibraryService} from '../../../book/service/library.service';
import {WantedBookDto, WantedBookStatus} from '../../model/wanted.model';
import {TagColor, TagComponent} from '../../../../shared/components/tag/tag.component';

const STATUS_COLOR_MAP: Record<WantedBookStatus, TagColor> = {
  ACTIVE: 'blue',
  GRABBED: 'amber',
  FULFILLED: 'green',
  PAUSED: 'gray',
};

@Component({
  selector: 'app-wanted-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ConfirmationService],
  imports: [TableModule, Button, ConfirmDialog, TranslocoDirective, DatePipe, TagComponent],
  templateUrl: './wanted-list.component.html',
  styleUrl: './wanted-list.component.scss',
})
export class WantedListComponent {
  private readonly wantedBookProgressService = inject(WantedBookProgressService);
  private readonly wantedBookService = inject(WantedBookService);
  private readonly dialogLauncherService = inject(DialogLauncherService);
  private readonly libraryService = inject(LibraryService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  readonly entries = computed(() =>
    [...this.wantedBookProgressService.entries()].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  );

  private readonly libraryNameById = computed(() => {
    const map = new Map<number, string>();
    for (const library of this.libraryService.libraries()) {
      if (library.id != null) {
        map.set(library.id, library.name);
      }
    }
    return map;
  });

  readonly pendingActionId = signal<number | null>(null);

  statusColor(status: WantedBookStatus): TagColor {
    return STATUS_COLOR_MAP[status];
  }

  libraryName(entry: WantedBookDto): string {
    return this.libraryNameById().get(entry.libraryId) ?? '—';
  }

  canTogglePause(entry: WantedBookDto): boolean {
    return entry.status === 'ACTIVE' || entry.status === 'PAUSED';
  }

  isPending(entry: WantedBookDto): boolean {
    return this.pendingActionId() === entry.id;
  }

  openCreateDialog(): void {
    void this.dialogLauncherService.openWantedBookCreateDialog().catch(() => undefined);
  }

  togglePause(entry: WantedBookDto): void {
    if (this.isPending(entry)) {
      return;
    }

    const action$ = entry.status === 'PAUSED'
      ? this.wantedBookService.resume(entry.id)
      : this.wantedBookService.pause(entry.id);

    this.pendingActionId.set(entry.id);
    action$.subscribe({
      next: (updated) => {
        this.pendingActionId.set(null);
        this.wantedBookProgressService.handleIncomingUpdate(updated);
      },
      error: () => {
        this.pendingActionId.set(null);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('wanted.toast.actionErrorSummary'),
          detail: this.t.translate('wanted.toast.actionErrorDetail'),
        });
      },
    });
  }

  confirmDelete(entry: WantedBookDto): void {
    this.confirmationService.confirm({
      message: this.t.translate('wanted.confirmDelete.message', {title: entry.title}),
      header: this.t.translate('wanted.confirmDelete.header'),
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.delete(entry),
    });
  }

  private delete(entry: WantedBookDto): void {
    this.pendingActionId.set(entry.id);
    this.wantedBookService.delete(entry.id).subscribe({
      next: () => {
        this.pendingActionId.set(null);
        this.wantedBookProgressService.removeEntry(entry.id);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('wanted.toast.deleteSuccessSummary'),
        });
      },
      error: () => {
        this.pendingActionId.set(null);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('wanted.toast.actionErrorSummary'),
          detail: this.t.translate('wanted.toast.actionErrorDetail'),
        });
      },
    });
  }
}
