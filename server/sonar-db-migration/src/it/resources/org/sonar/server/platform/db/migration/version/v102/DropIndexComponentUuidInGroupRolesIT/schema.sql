CREATE TABLE "GROUP_ROLES"(
    "UUID" CHARACTER VARYING(40) NOT NULL,
    "ROLE" CHARACTER VARYING(64) NOT NULL,
    "COMPONENT_UUID" CHARACTER VARYING(40),
    "GROUP_UUID" CHARACTER VARYING(40)
);
ALTER TABLE "GROUP_ROLES" ADD CONSTRAINT "PK_GROUP_ROLES" PRIMARY KEY("UUID");
CREATE INDEX "GROUP_ROLES_COMPONENT_UUID" ON "GROUP_ROLES"("COMPONENT_UUID" NULLS FIRST);
CREATE UNIQUE INDEX "UNIQ_GROUP_ROLES" ON "GROUP_ROLES"("GROUP_UUID" NULLS FIRST, "COMPONENT_UUID" NULLS FIRST, "ROLE" NULLS FIRST);
