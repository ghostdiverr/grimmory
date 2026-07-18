import {Component, DestroyRef, inject, OnInit} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {KeyValuePipe} from '@angular/common';
import {Button} from 'primeng/button';
import {Divider} from 'primeng/divider';
import {Tag} from 'primeng/tag';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

import {AcquisitionJobDto, AcquisitionJobStatus} from '../../../features/acquisition/model/acquisition.model';
import {AcquisitionProgressService} from '../../service/acquisition-progress.service';

@Component({
  selector: 'app-acquisition-job-widget',
  templateUrl: './acquisition-job-widget-component.html',
  styleUrls: ['./acquisition-job-widget-component.scss'],
  standalone: true,
  imports: [KeyValuePipe, Button, Divider, Tag, TranslocoDirective]
})
export class AcquisitionJobWidgetComponent implements OnInit {
  activeJobs: Record<number, AcquisitionJobDto> = {};

  private readonly destroyRef = inject(DestroyRef);
  private acquisitionProgressService = inject(AcquisitionProgressService);
  private readonly t = inject(TranslocoService);

  ngOnInit(): void {
    this.acquisitionProgressService.activeJobs$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(jobs => {
        this.activeJobs = jobs;
      });
  }

  dismissJob(id: number): void {
    this.acquisitionProgressService.dismissJob(id);
  }

  private readonly statusLabelKeys: Record<AcquisitionJobStatus, string> = {
    GRABBED: 'acquisition.tray.statusGrabbed',
    WAITING_FOR_FILE: 'acquisition.tray.statusWaitingForFile',
    NORMALIZING: 'acquisition.tray.statusNormalizing',
    IMPORTING: 'acquisition.tray.statusImporting',
    COMPLETED: 'acquisition.tray.statusCompleted',
    FAILED: 'acquisition.tray.statusFailed',
    TIMED_OUT: 'acquisition.tray.statusTimedOut',
  };

  getStatusLabel(status: AcquisitionJobStatus): string {
    const key = this.statusLabelKeys[status];
    return key ? this.t.translate(key) : status;
  }

  getTagSeverity(status: AcquisitionJobStatus): 'info' | 'success' | 'danger' {
    switch (status) {
      case 'COMPLETED':
        return 'success';
      case 'FAILED':
      case 'TIMED_OUT':
        return 'danger';
      case 'GRABBED':
      case 'WAITING_FOR_FILE':
      case 'NORMALIZING':
      case 'IMPORTING':
      default:
        return 'info';
    }
  }

  getCategoryLabel(category: AcquisitionJobDto['category']): string {
    if (category === 'AUDIOBOOK') {
      return this.t.translate('acquisition.tray.categoryAudiobook');
    }
    return this.t.translate('acquisition.tray.categoryBook');
  }

  protected readonly Object = Object;
}
