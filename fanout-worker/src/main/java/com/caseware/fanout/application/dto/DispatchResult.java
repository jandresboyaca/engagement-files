package com.caseware.fanout.application.dto;

public record DispatchResult(int leasesExpired, int loadsStarted, int claimsSkipped) {
}
