package com.agorapulse.micronaut.http.examples.planets

import com.amazonaws.AmazonClientException
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression
import com.amazonaws.services.dynamodbv2.datamodeling.IDynamoDBMapper
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput

import javax.annotation.PostConstruct
import javax.inject.Singleton

/**
 * Service to access DynamoDB entities.
 */
@Singleton
class PlanetDBService {

    AmazonDynamoDB amazonDynamoDBClient
    IDynamoDBMapper mapper

    PlanetDBService(AmazonDynamoDB amazonDynamoDBClient, IDynamoDBMapper mapper) {
        this.amazonDynamoDBClient = amazonDynamoDBClient
        this.mapper = mapper
    }

    @PostConstruct
    void init() {
        try {
            amazonDynamoDBClient.createTable(mapper.generateCreateTableRequest(Planet).withProvisionedThroughput(
                new ProvisionedThroughput().withReadCapacityUnits(5).withWriteCapacityUnits(1)
            ))
        } catch (AmazonClientException ignored) {
            // ok, already exits
        }
    }

    void save(Planet planet) {
        mapper.save(planet)
    }

    void delete(Planet planet) {
        mapper.delete(planet)
    }

    List<Planet> findAllByStar(String starName) {
        mapper.query(Planet, new DynamoDBQueryExpression<Planet>().withHashKeyValues(new Planet(star: starName)))
    }

    Planet get(String starName, String planetName) {
        mapper.load(Planet, starName, planetName)
    }

}