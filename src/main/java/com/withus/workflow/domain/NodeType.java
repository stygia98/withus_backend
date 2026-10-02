package com.withus.workflow.domain;

/** workflow_step.node_type (DB_SCHEMA 11번) */
public enum NodeType {
	TRIGGER, WAIT, CONDITION, SEND_EMAIL, SEND_SMS, END
}
