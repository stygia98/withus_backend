package com.withus.workflow.domain;

/** workflow_instance.status (DB_SCHEMA 12번) */
public enum InstanceStatus {
	WAITING, RUNNING, COMPLETED, FAILED, CANCELLED
}
