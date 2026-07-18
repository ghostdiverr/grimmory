import {Component, inject, OnInit, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {Button} from 'primeng/button';
import {InputText} from 'primeng/inputtext';
import {SelectButton} from 'primeng/selectbutton';
import {TableModule} from 'primeng/table';
import {Tooltip} from 'primeng/tooltip';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {AcquisitionService} from '../../service/acquisition.service';
import {AcquisitionCategory, AcquisitionSearchSeed, GrabRequest, ProwlarrReleaseDto} from '../../model/acquisition.model';
import {formatBytes} from '../../util/format-bytes';
import {parseReleaseQuality} from '../../util/parse-release-quality';

interface CategoryOption {
  label: string;
  value: AcquisitionCategory;
}

@Component({
  selector: 'app-acquisition-search-dialog',
  standalone: true,
  templateUrl: './acquisition-search-dialog.component.html',
  styleUrl: './acquisition-search-dialog.component.scss',
  imports: [FormsModule, Button, InputText, SelectButton, TableModule, Tooltip, TranslocoDirective],
})
export class AcquisitionSearchDialogComponent implements OnInit {
  private readonly config = inject(DynamicDialogConfig);
  private readonly dialogRef = inject(DynamicDialogRef);
  private readonly acquisitionService = inject(AcquisitionService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  private readonly seed: AcquisitionSearchSeed = this.config.data ?? {category: 'BOOK'};

  readonly categoryOptions: CategoryOption[] = [
    {label: this.t.translate('acquisition.searchDialog.categoryBook'), value: 'BOOK'},
    {label: this.t.translate('acquisition.searchDialog.categoryAudiobook'), value: 'AUDIOBOOK'},
  ];

  readonly query = signal(this.buildInitialQuery());
  readonly category = signal<AcquisitionCategory>(this.seed.category ?? 'BOOK');
  readonly results = signal<ProwlarrReleaseDto[]>([]);
  readonly loading = signal(false);
  readonly searched = signal(false);
  readonly grabbingGuid = signal<string | null>(null);
  readonly grabbedGuids = signal<Set<string>>(new Set());

  /** Default sort: highest-seeded releases first. */
  readonly sortField = 'seeders';
  readonly sortOrder = -1;

  readonly formatBytes = formatBytes;
  readonly parseReleaseQuality = parseReleaseQuality;

  ngOnInit(): void {
    if (this.query().trim().length > 0) {
      this.onSearch();
    }
  }

  private buildInitialQuery(): string {
    return [this.seed.title, this.seed.author].filter(Boolean).join(' ').trim();
  }

  onQueryChange(value: string): void {
    this.query.set(value);
  }

  onCategoryChange(value: AcquisitionCategory): void {
    this.category.set(value);
    if (this.searched()) {
      this.onSearch();
    }
  }

  onSearch(): void {
    const query = this.query().trim();
    if (!query) {
      return;
    }

    this.loading.set(true);
    this.searched.set(true);

    this.acquisitionService.search(query, this.category()).subscribe({
      next: releases => {
        this.results.set(releases ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.results.set([]);
        this.loading.set(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('acquisition.searchDialog.toast.searchErrorSummary'),
          detail: this.t.translate('acquisition.searchDialog.toast.searchErrorDetail'),
        });
      },
    });
  }

  isGrabbed(release: ProwlarrReleaseDto): boolean {
    return this.grabbedGuids().has(release.guid);
  }

  isGrabbing(release: ProwlarrReleaseDto): boolean {
    return this.grabbingGuid() === release.guid;
  }

  grab(release: ProwlarrReleaseDto): void {
    if (this.isGrabbing(release) || this.isGrabbed(release)) {
      return;
    }

    this.grabbingGuid.set(release.guid);

    const request: GrabRequest = {
      ...release,
      bookId: this.seed.bookId,
    };

    this.acquisitionService.grab(request).subscribe({
      next: job => {
        this.grabbingGuid.set(null);
        this.grabbedGuids.update(current => new Set(current).add(release.guid));
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('acquisition.searchDialog.toast.grabSuccessSummary'),
          detail: this.t.translate('acquisition.searchDialog.toast.grabSuccessDetail', {
            title: release.title,
            status: job?.status ?? '',
          }),
        });
      },
      error: () => {
        this.grabbingGuid.set(null);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('acquisition.searchDialog.toast.grabErrorSummary'),
          detail: this.t.translate('acquisition.searchDialog.toast.grabErrorDetail', {
            title: release.title,
          }),
        });
      },
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}
