// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2025 MariaDB Corporation Ab
package org.mariadb.jdbc.message.client;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.mariadb.jdbc.client.Context;
import org.mariadb.jdbc.client.socket.Writer;
import org.mariadb.jdbc.client.util.Parameter;
import org.mariadb.jdbc.client.util.Parameters;
import org.mariadb.jdbc.message.ClientMessage;
import org.mariadb.jdbc.plugin.codec.ByteArrayCodec;
import org.mariadb.jdbc.util.ClientParser;

/**
 * Query client packet COM_QUERY see https://mariadb.com/kb/en/com_query/ same than QueryPacket, but
 * with parameters that will be escaped
 */
public final class QueryWithParametersPacket implements RedoableClientMessage {

  private final String preSqlCmd;
  private final ClientParser parser;
  private final InputStream localInfileInputStream;
  private List<Parameters> parametersList;

  /**
   * Constructor
   *
   * @param preSqlCmd additional pre command
   * @param parser command parser result
   * @param parameters parameters
   * @param localInfileInputStream local infile input stream
   */
  public QueryWithParametersPacket(
      String preSqlCmd,
      ClientParser parser,
      Parameters parameters,
      InputStream localInfileInputStream) {
    this.preSqlCmd = preSqlCmd;
    this.parser = parser;
    this.parametersList = List.of(parameters);
    this.localInfileInputStream = localInfileInputStream;
  }
  
  public QueryWithParametersPacket(
      String preSqlCmd,
      ClientParser parser,
      List<Parameters> parametersList,
      InputStream localInfileInputStream) {
    this.preSqlCmd = preSqlCmd;
    this.parser = parser;
    this.parametersList = parametersList;
    this.localInfileInputStream = localInfileInputStream;
  }

  @Override
  public void ensureReplayable(Context context) throws IOException, SQLException {
	for (int j = 0; j < parametersList.size(); j++) {
	  Parameters parameters = parametersList.get(j);
      int parameterCount = parameters.size();
      for (int i = 0; i < parameterCount; i++) {
        Parameter p = parameters.get(i);
        if (!p.isNull() && p.canEncodeLongData()) {
          parameters.set(
            i, new org.mariadb.jdbc.codec.Parameter<>(ByteArrayCodec.INSTANCE, p.encodeData()));
        }
      }
	}
  }

  public void saveParameters() {
	List<Parameters> clonedParameterList = new ArrayList<Parameters>(parametersList.size());
	for (int j = 0; j < parametersList.size(); j++) {
		clonedParameterList.add(parametersList.get(j).clone());
	}
    this.parametersList = clonedParameterList;
  }

  @Override
  public int encode(Writer encoder, Context context) throws IOException, SQLException {
    encoder.initPacket();
    encoder.writeByte(0x03);
    if (preSqlCmd != null) encoder.writeAscii(preSqlCmd);
    if (parser.getParamPositions().size() == 0) {
      encoder.writeBytes(parser.getQuery());
    } else if (parser.getValuesBracketPositions() == null || parametersList.size() == 1) {
      Parameters parameters = parametersList.get(0);
      int pos = 0;
      int paramPos;
      for (int i = 0; i < parser.getParamPositions().size(); i++) {
        paramPos = parser.getParamPositions().get(i);
        encoder.writeBytes(parser.getQuery(), pos, paramPos - pos);
        pos = paramPos + 1;
        parameters.get(i).encodeText(encoder, context);
      }
      encoder.writeBytes(parser.getQuery(), pos, parser.getQuery().length - pos);
    } else {
      // do the rewriting here
      int startValuePos = parser.getValuesBracketPositions().get(0);
      int endValuePos = parser.getValuesBracketPositions().get(1);
      int parameterListIdx = 0;
      
      Parameters parameters = parametersList.get(parameterListIdx);
      int startIdx = 0;
      int returnIdx; // maybe calc outside the loop
      for (int i = startIdx; i < parser.getParamPositions().size(); i++) {
    	  if (parser.getParamPositions().get(i) >= startValuePos) {
    		  returnIdx = i; break;
    	  }
      }
      
      int pos = 0; // byte position
      int paramPos; // placeholder position
      for (int j = 0; j < parametersList.size(); j++) {
        for (int i = startIdx; i < parser.getParamPositions().size(); i++) {
          paramPos = parser.getParamPositions().get(i);
          if (paramPos < startValuePos) {
	          encoder.writeBytes(parser.getQuery(), pos, paramPos - pos);
	          pos = paramPos + 1;
	          parameters.get(i).encodeText(encoder, context);
          } else if (paramPos < endValuePos) {
        	  encoder.writeBytes(parser.getQuery(), pos, paramPos - pos);
	          pos = paramPos + 1;
	          parameters.get(i).encodeText(encoder, context);
          }
        }
        if (j < parametersList.size() - 1) {
        	encoder.writeBytes(parser.getQuery(), pos, endValuePos - pos);
        	// there should be a comma here but the old code didn't have one. oh yes it did.
        	
        	
        }
      }
      encoder.writeBytes(parser.getQuery(), pos, parser.getQuery().length - pos);
    	
    	
    }
    encoder.flush();
    return 1;
  }

  public int batchUpdateLength() {
    return 1;
  }

  public boolean validateLocalFileName(String fileName, Context context) {
    return ClientMessage.validateLocalFileName(parser.getSql(), parameters, fileName, context);
  }

  public InputStream getLocalInfileInputStream() {
    return localInfileInputStream;
  }

  @Override
  public String description() {
    return parser.getSql();
  }
}
