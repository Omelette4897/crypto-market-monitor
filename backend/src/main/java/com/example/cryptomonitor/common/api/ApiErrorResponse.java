package com.example.cryptomonitor.common.api;

/** API失敗時の公開契約。内部例外・SQL・接続情報はこのDTOに格納しない。 */
public record ApiErrorResponse(String code, String message) {}
