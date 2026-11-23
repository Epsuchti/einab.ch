import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, BaseRouteReuseStrategy } from '@angular/router';

/** Switching to another Bezirk rebuilds its pages, so they load that Bezirk's data instead of keeping the old one. */
@Injectable()
export class BezirkRouteReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(future: ActivatedRouteSnapshot, curr: ActivatedRouteSnapshot): boolean {
    return super.shouldReuseRoute(future, curr) && future.params['bezirkSlug'] === curr.params['bezirkSlug'];
  }
}
