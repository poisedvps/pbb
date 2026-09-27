package com.hospital.pbb.common;

import java.util.List;

/** 分页返回体：total 是符合条件的总行数，items 是当前页。 */
public record PageVO<T>(long total, List<T> items) {
}
