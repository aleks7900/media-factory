package com.mediafactory.stock;

import java.util.*;

public interface StockExportAdapter {
  String platformId();

  byte[] csv(List<Map<String, Object>> items, Map<String, Object> profile);
}
