--
-- Copyright 2002-2015 SCOOP Software GmbH
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

create table COP_WORKFLOW_INSTANCE  (
   ID           		VARCHAR(128) not null,
   STATE                SMALLINT not null,
   PRIORITY             SMALLINT not null,
   LAST_MOD_TS          TIMESTAMP not null,
   PPOOL_ID      		VARCHAR(32) not null,
   DATA					VARCHAR(32672),
   OBJECT_STATE			VARCHAR(32672),
   CS_WAITMODE			SMALLINT,
   MIN_NUMB_OF_RESP		SMALLINT,
   NUMB_OF_WAITS		SMALLINT,
   TIMEOUT				TIMESTAMP,
   CREATION_TS			TIMESTAMP not null,
   CLASSNAME			VARCHAR(512) not null,
   PRIMARY KEY (ID)
);

create table COP_WORKFLOW_INSTANCE_ERROR (
   WORKFLOW_INSTANCE_ID		VARCHAR(128)	not null,
   "EXCEPTION"				VARCHAR(32672)	not null,
   ERROR_TS     	   		TIMESTAMP       not null
);

create index IDX_COP_WFID_WFID on COP_WORKFLOW_INSTANCE_ERROR (
   WORKFLOW_INSTANCE_ID
);

--
-- RESPONSE
--
create table COP_RESPONSE  (
   RESPONSE_ID		VARCHAR(128) not null,
   CORRELATION_ID	VARCHAR(128) not null,
   RESPONSE_TS		TIMESTAMP not null,
   RESPONSE			VARCHAR(32672),
   RESPONSE_TIMEOUT	 TIMESTAMP,
   RESPONSE_META_DATA VARCHAR(4000),
   PRIMARY KEY (RESPONSE_ID)
);

create index IDX_COP_RESP_CID on COP_RESPONSE (
   CORRELATION_ID
);
 
--
-- WAIT
--
create table COP_WAIT (
   	CORRELATION_ID			VARCHAR(128) not null,
   	WORKFLOW_INSTANCE_ID  	VARCHAR(128) not null,
	MIN_NUMB_OF_RESP		SMALLINT not null,
	TIMEOUT_TS				TIMESTAMP,
   	STATE					SMALLINT not null,
    PRIORITY            	SMALLINT not null,
    PPOOL_ID      			VARCHAR(32) not null,
    PRIMARY KEY (CORRELATION_ID)
);


create index IDX_COP_WAIT_WFI_ID on COP_WAIT (
   WORKFLOW_INSTANCE_ID
);

--
-- QUEUE
--
create table COP_QUEUE (
   PPOOL_ID      		VARCHAR(32)						not null,
   PRIORITY             SMALLINT                        not null,
   LAST_MOD_TS          TIMESTAMP                       not null,
   WORKFLOW_INSTANCE_ID	VARCHAR(128) 					not null,
   ENGINE_ID            VARCHAR(16),
   PRIMARY KEY (WORKFLOW_INSTANCE_ID)
);

create table COP_AUDIT_TRAIL_EVENT (
	SEQ_ID 					BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
	OCCURRENCE				TIMESTAMP NOT NULL,
	CONVERSATION_ID 		VARCHAR(64) NOT NULL,
	LOGLEVEL				SMALLINT NOT NULL,
	CONTEXT					VARCHAR(128) NOT NULL,
	INSTANCE_ID				VARCHAR(128),
	CORRELATION_ID 			VARCHAR(128),
	TRANSACTION_ID 			VARCHAR(128),
	LONG_MESSAGE 			CLOB,
	MESSAGE_TYPE			VARCHAR(256),
    PRIMARY KEY (SEQ_ID)
);


CREATE TABLE COP_ADAPTERCALL (WORKFLOWID  VARCHAR(128) NOT NULL,
                          ENTITYID    VARCHAR(128) NOT NULL,
                          ADAPTERID   VARCHAR(256) NOT NULL,
                          PRIORITY    BIGINT NOT NULL,
                          DEFUNCT     CHAR(1) DEFAULT '0' NOT NULL ,
                          DEQUEUE_TS  TIMESTAMP , 
                          METHODDECLARINGCLASS VARCHAR(1024)  NOT NULL,
                          METHODNAME VARCHAR(1024)  NOT NULL,
                          METHODSIGNATURE VARCHAR(2048)  NOT NULL,
                          ARGS CLOB,
                          CONSTRAINT PK_ADAPTERCLASS PRIMARY KEY (ADAPTERID, WORKFLOWID, ENTITYID));

CREATE INDEX COP_IDX_ADAPTERCALL ON COP_ADAPTERCALL(ADAPTERID, PRIORITY);


--
-- COP_LOCK
--
create table COP_LOCK (
	LOCK_ID 				VARCHAR(128) NOT NULL, 
	CORRELATION_ID 			VARCHAR(128) NOT NULL, 
	WORKFLOW_INSTANCE_ID 	VARCHAR(128) NOT NULL, 
	INSERT_TS 				TIMESTAMP NOT NULL, 
	REPLY_SENT 				CHAR(1) NOT NULL,
    PRIMARY KEY (LOCK_ID,WORKFLOW_INSTANCE_ID)
);
