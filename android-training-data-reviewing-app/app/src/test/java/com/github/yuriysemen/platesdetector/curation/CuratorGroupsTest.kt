package com.github.yuriysemen.platesdetector.curation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratorGroupsTest {

    @Test
    fun extractsCuratorsGroup() {
        val claims = JSONObject("""{"sub":"abc","cognito:groups":["curators"]}""")
        val groups = cognitoGroupsFromClaims(claims)
        assertEquals(listOf("curators"), groups)
        assertTrue(CuratorAuthManager.CURATORS_GROUP in groups)
    }

    @Test
    fun missingClaimYieldsEmptyList() {
        val groups = cognitoGroupsFromClaims(JSONObject("""{"sub":"abc"}"""))
        assertTrue(groups.isEmpty())
        assertFalse(CuratorAuthManager.CURATORS_GROUP in groups)
    }

    @Test
    fun handlesMultipleGroups() {
        val claims = JSONObject("""{"cognito:groups":["admins","curators","beta"]}""")
        assertEquals(listOf("admins", "curators", "beta"), cognitoGroupsFromClaims(claims))
    }
}
