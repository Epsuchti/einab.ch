import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { defer, finalize } from 'rxjs';

import { HttpActivityService } from './http-activity.service';

export const loadingInterceptor: HttpInterceptorFn = (request, next) => {
  const activity = inject(HttpActivityService);
  return defer(() => {
    activity.start();
    return next(request).pipe(finalize(() => activity.end()));
  });
};
