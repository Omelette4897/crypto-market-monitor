package com.example.cryptomonitor.collection.entity;

/** DBの実行状態。MapperのACCEPTEDは保存前の判定なので、このenumとは区別する。 */
public enum CollectionRunStatus {
    RUNNING, SUCCESS, FAILED, SKIPPED, INTERRUPTED
}
