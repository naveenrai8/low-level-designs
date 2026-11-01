package com.lld.dispatch;

/**
 * What a scoring strategy may know about a driver.
 *
 * @param id the driver id
 * @param where the last reported position
 * @param ratingTenths the rating in tenths of a star, 0..50 (47 means 4.7 stars)
 * @param speedMps the typical speed in whole metres per second, at least 1
 */
public record DriverInfo(long id, Point where, int ratingTenths, int speedMps) {
}
